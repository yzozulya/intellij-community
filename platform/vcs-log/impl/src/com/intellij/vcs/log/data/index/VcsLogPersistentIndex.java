// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.vcs.log.data.index;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.AbstractVcs;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.VcsNotifier;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Consumer;
import com.intellij.util.EmptyConsumer;
import com.intellij.util.ThrowableRunnable;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.indexing.StorageException;
import com.intellij.util.io.*;
import com.intellij.vcs.log.VcsLogIndexService;
import com.intellij.vcs.log.VcsLogProperties;
import com.intellij.vcs.log.VcsLogProvider;
import com.intellij.vcs.log.VcsUserRegistry;
import com.intellij.vcs.log.data.SingleTaskController;
import com.intellij.vcs.log.data.VcsLogProgress;
import com.intellij.vcs.log.data.VcsLogStorage;
import com.intellij.vcs.log.data.VcsLogStorageImpl;
import com.intellij.vcs.log.impl.FatalErrorHandler;
import com.intellij.vcs.log.impl.HeavyAwareExecutor;
import com.intellij.vcs.log.impl.VcsIndexableLogProvider;
import com.intellij.vcs.log.impl.VcsLogIndexer;
import com.intellij.vcs.log.statistics.VcsLogIndexCollector;
import com.intellij.vcs.log.util.*;
import com.intellij.vcsUtil.VcsUtil;
import gnu.trove.TIntHashSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static com.intellij.vcs.log.data.index.VcsLogFullDetailsIndex.INDEX;
import static com.intellij.vcs.log.util.PersistentUtil.calcLogId;

public class VcsLogPersistentIndex implements VcsLogModifiableIndex, Disposable {
  private static final Logger LOG = Logger.getInstance(VcsLogPersistentIndex.class);
  private static final int VERSION = 14;
  private static final VcsLogProgress.ProgressKey INDEXING = new VcsLogProgress.ProgressKey("index");

  @NotNull private final Project myProject;
  @NotNull private final FatalErrorHandler myFatalErrorsConsumer;
  @NotNull private final VcsLogProgress myProgress;
  @NotNull private final Map<VirtualFile, VcsLogIndexer> myIndexers;
  @NotNull private final VcsLogStorage myStorage;
  @NotNull private final Set<VirtualFile> myRoots;
  @NotNull private final VcsLogBigRepositoriesList myBigRepositoriesList;
  @NotNull private final VcsLogIndexCollector myIndexCollector;

  @Nullable private final IndexStorage myIndexStorage;
  @Nullable private final IndexDataGetter myDataGetter;

  @NotNull private final SingleTaskController<IndexingRequest, Void> mySingleTaskController;
  @NotNull private final Map<VirtualFile, AtomicInteger> myNumberOfTasks = new HashMap<>();
  @NotNull private final Map<VirtualFile, AtomicLong> myIndexingTime = new HashMap<>();
  @NotNull private final Map<VirtualFile, AtomicInteger> myIndexingLimit = new HashMap<>();

  @NotNull private final List<IndexingFinishedListener> myListeners = ContainerUtil.createLockFreeCopyOnWriteList();

  @NotNull private Map<VirtualFile, TIntHashSet> myCommitsToIndex = new HashMap<>();

  public VcsLogPersistentIndex(@NotNull Project project,
                               @NotNull VcsLogStorage storage,
                               @NotNull VcsLogProgress progress,
                               @NotNull Map<VirtualFile, VcsLogProvider> providers,
                               @NotNull FatalErrorHandler fatalErrorsConsumer,
                               @NotNull Disposable disposableParent) {
    myStorage = storage;
    myProject = project;
    myProgress = progress;
    myFatalErrorsConsumer = fatalErrorsConsumer;
    myBigRepositoriesList = VcsLogBigRepositoriesList.getInstance();
    myIndexCollector = VcsLogIndexCollector.getInstance(myProject);

    myIndexers = getAvailableIndexers(providers);
    myRoots = new LinkedHashSet<>(myIndexers.keySet());

    VcsUserRegistry userRegistry = ServiceManager.getService(myProject, VcsUserRegistry.class);

    myIndexStorage = createIndexStorage(fatalErrorsConsumer, calcLogId(myProject, providers), userRegistry);
    if (myIndexStorage != null) {
      myDataGetter = new IndexDataGetter(myProject, myRoots, myIndexStorage, myStorage, myFatalErrorsConsumer);
    }
    else {
      myDataGetter = null;
    }

    for (VirtualFile root : myRoots) {
      myNumberOfTasks.put(root, new AtomicInteger());
      myIndexingTime.put(root, new AtomicLong());
      myIndexingLimit.put(root, new AtomicInteger(getIndexingLimit()));
    }

    mySingleTaskController = new MySingleTaskController(project, myIndexStorage != null ? myIndexStorage : this);

    Disposer.register(disposableParent, this);
  }

  private static int getIndexingLimit() {
    return Math.max(1, Registry.intValue("vcs.log.index.limit.minutes"));
  }

  protected IndexStorage createIndexStorage(@NotNull FatalErrorHandler fatalErrorHandler,
                                            @NotNull String logId, @NotNull VcsUserRegistry registry) {
    try {
      return IOUtil.openCleanOrResetBroken(() -> new IndexStorage(logId, myStorage, registry, fatalErrorHandler, this),
                                           () -> IndexStorage.cleanup(logId));
    }
    catch (IOException e) {
      myFatalErrorsConsumer.consume(this, e);
    }
    return null;
  }

  @Override
  public void scheduleIndex(boolean full) {
    doScheduleIndex(full, request -> mySingleTaskController.request(request));
  }

  @TestOnly
  void indexNow(boolean full) {
    doScheduleIndex(full, request -> request.run(myProgress.createProgressIndicator(INDEXING)));
  }

  private synchronized void doScheduleIndex(boolean full, @NotNull Consumer<IndexingRequest> requestConsumer) {
    if (Disposer.isDisposed(this)) return;
    if (myCommitsToIndex.isEmpty() || myIndexStorage == null) return;
    // for fresh index, wait for complete log to load and index everything in one command
    if (myIndexStorage.isFresh() && !full) return;
    Map<VirtualFile, TIntHashSet> commitsToIndex = myCommitsToIndex;

    for (VirtualFile root : commitsToIndex.keySet()) {
      myNumberOfTasks.get(root).incrementAndGet();
    }
    myCommitsToIndex = new HashMap<>();

    boolean isFull = full && myIndexStorage.isFresh();
    if (isFull) LOG.debug("Index storage for project " + myProject.getName() + " is fresh, scheduling full reindex");
    for (VirtualFile root : commitsToIndex.keySet()) {
      TIntHashSet commits = commitsToIndex.get(root);
      if (commits.isEmpty()) continue;

      if (myBigRepositoriesList.isBig(root)) {
        myCommitsToIndex.put(root, commits); // put commits back in order to be able to reindex
        LOG.info("Indexing repository " + root.getName() + " is skipped since it is too big");
        continue;
      }

      requestConsumer.consume(new IndexingRequest(root, myIndexStorage.paths.getPathsEncoder(), commits, isFull));
    }

    if (isFull) {
      myIndexCollector.reportFreshIndex();
      myIndexStorage.unmarkFresh();
    }
  }

  private void storeDetail(@NotNull VcsLogIndexer.CompressedDetails detail) {
    if (myIndexStorage == null) return;
    try {
      int index = myStorage.getCommitIndex(detail.getId(), detail.getRoot());

      myIndexStorage.messages.put(index, detail.getFullMessage());
      myIndexStorage.trigrams.update(index, detail);
      myIndexStorage.users.update(index, detail);
      myIndexStorage.paths.update(index, detail);
      myIndexStorage.parents.put(index, ContainerUtil.map(detail.getParents(), p -> myStorage.getCommitIndex(p, detail.getRoot())));
      // we know the whole graph without timestamps now
      if (!detail.getAuthor().equals(detail.getCommitter())) {
        myIndexStorage.committers.put(index, myIndexStorage.users.getUserId(detail.getCommitter()));
      }
      myIndexStorage.timestamps.put(index, Pair.create(detail.getAuthorTime(), detail.getCommitTime()));

      myIndexStorage.commits.put(index);
    }
    catch (IOException e) {
      myFatalErrorsConsumer.consume(this, e);
    }
  }

  private void flush() {
    try {
      if (myIndexStorage != null) {
        myIndexStorage.messages.force();
        myIndexStorage.trigrams.flush();
        myIndexStorage.users.flush();
        myIndexStorage.paths.flush();
        myIndexStorage.parents.force();
        myIndexStorage.commits.flush();
        myIndexStorage.committers.force();
        myIndexStorage.timestamps.force();
      }
    }
    catch (StorageException e) {
      myFatalErrorsConsumer.consume(this, e);
    }
  }

  @Override
  public void markCorrupted() {
    if (myIndexStorage != null) myIndexStorage.commits.markCorrupted();
  }

  @Override
  public boolean isIndexed(int commit) {
    try {
      return myIndexStorage == null || myIndexStorage.commits.contains(commit);
    }
    catch (IOException e) {
      myFatalErrorsConsumer.consume(this, e);
    }
    return false;
  }
  
  @Override
  public synchronized boolean isIndexed(@NotNull VirtualFile root) {
    return isIndexingEnabled(root) &&
           (!myCommitsToIndex.containsKey(root) && myNumberOfTasks.get(root).get() == 0);
  }

  @Override
  public boolean isIndexingEnabled(@NotNull VirtualFile root) {
    if (myIndexStorage == null) return false;
    return myRoots.contains(root) && !(myBigRepositoriesList.isBig(root));
  }

  @Override
  public synchronized void markForIndexing(int index, @NotNull VirtualFile root) {
    if (isIndexed(index) || !myRoots.contains(root)) return;
    TroveUtil.add(myCommitsToIndex, root, index);
  }

  @Nullable
  @Override
  public IndexDataGetter getDataGetter() {
    if (myIndexStorage == null) return null;
    return myDataGetter;
  }

  @Override
  public void addListener(@NotNull IndexingFinishedListener l) {
    myListeners.add(l);
  }

  @Override
  public void removeListener(@NotNull IndexingFinishedListener l) {
    myListeners.remove(l);
  }

  @Override
  public void dispose() {
  }

  @NotNull
  private static Map<VirtualFile, VcsLogIndexer> getAvailableIndexers(@NotNull Map<VirtualFile, VcsLogProvider> providers) {
    Map<VirtualFile, VcsLogIndexer> indexers = new LinkedHashMap<>();
    for (Map.Entry<VirtualFile, VcsLogProvider> entry : providers.entrySet()) {
      VirtualFile root = entry.getKey();
      VcsLogProvider provider = entry.getValue();
      if (VcsLogProperties.get(provider, VcsLogProperties.SUPPORTS_INDEXING) && provider instanceof VcsIndexableLogProvider) {
        indexers.put(root, ((VcsIndexableLogProvider)provider).getIndexer());
      }
    }
    return indexers;
  }

  @NotNull
  public static Set<VirtualFile> getRootsForIndexing(@NotNull Map<VirtualFile, VcsLogProvider> providers) {
    return getAvailableIndexers(providers).keySet();
  }

  static class IndexStorage implements Disposable {
    private static final String COMMITS = "commits";
    private static final String MESSAGES = "messages";
    private static final String PARENTS = "parents";
    private static final String COMMITTERS = "committers";
    private static final String TIMESTAMPS = "timestamps";
    private static final int MESSAGES_VERSION = 0;
    @NotNull public final PersistentSet<Integer> commits;
    @NotNull public final PersistentMap<Integer, String> messages;
    @NotNull public final PersistentMap<Integer, List<Integer>> parents;
    @NotNull public final PersistentMap<Integer, Integer> committers;
    @NotNull public final PersistentMap<Integer, Pair<Long, Long>> timestamps;
    @NotNull public final VcsLogMessagesTrigramIndex trigrams;
    @NotNull public final VcsLogUserIndex users;
    @NotNull public final VcsLogPathsIndex paths;

    private volatile boolean myIsFresh;

    IndexStorage(@NotNull String logId,
                 @NotNull VcsLogStorage storage,
                 @NotNull VcsUserRegistry userRegistry,
                 @NotNull FatalErrorHandler fatalErrorHandler,
                 @NotNull Disposable parentDisposable)
      throws IOException {
      Disposer.register(parentDisposable, this);

      try {
        boolean forwardIndexRequired = VcsLogIndexService.isPathsForwardIndexRequired();
        StorageId storageId = new StorageId(INDEX, logId, getVersion(), new boolean[]{forwardIndexRequired});

        File commitsStorage = storageId.getStorageFile(COMMITS);
        myIsFresh = !commitsStorage.exists();
        commits = new PersistentSetImpl<>(commitsStorage, EnumeratorIntegerDescriptor.INSTANCE, Page.PAGE_SIZE, null,
                                          storageId.getVersion());
        Disposer.register(this, () -> catchAndWarn(commits::close));

        File messagesStorage = new StorageId(INDEX, logId, VcsLogStorageImpl.VERSION + MESSAGES_VERSION).getStorageFile(MESSAGES);
        messages = new PersistentHashMap<>(messagesStorage, EnumeratorIntegerDescriptor.INSTANCE, EnumeratorStringDescriptor.INSTANCE,
                                           Page.PAGE_SIZE);
        Disposer.register(this, () -> catchAndWarn(messages::close));

        trigrams = new VcsLogMessagesTrigramIndex(storageId, fatalErrorHandler, this);
        users = new VcsLogUserIndex(storageId, userRegistry, fatalErrorHandler, this);
        paths = new VcsLogPathsIndex(storageId, storage, fatalErrorHandler, this);

        File parentsStorage = storageId.getStorageFile(PARENTS);
        parents = new PersistentHashMap<>(parentsStorage, EnumeratorIntegerDescriptor.INSTANCE,
                                          new IntListDataExternalizer(), Page.PAGE_SIZE, storageId.getVersion());
        Disposer.register(this, () -> catchAndWarn(parents::close));

        File committersStorage = storageId.getStorageFile(COMMITTERS);
        committers = new PersistentHashMap<>(committersStorage, EnumeratorIntegerDescriptor.INSTANCE, EnumeratorIntegerDescriptor.INSTANCE,
                                             Page.PAGE_SIZE, storageId.getVersion());
        Disposer.register(this, () -> catchAndWarn(committers::close));

        File timestampsStorage = storageId.getStorageFile(TIMESTAMPS);
        timestamps = new PersistentHashMap<>(timestampsStorage, EnumeratorIntegerDescriptor.INSTANCE, new LongPairDataExternalizer(),
                                             Page.PAGE_SIZE, storageId.getVersion());
        Disposer.register(this, () -> catchAndWarn(timestamps::close));
      }
      catch (Throwable t) {
        Disposer.dispose(this);
        throw t;
      }
    }

    void markCorrupted() {
      catchAndWarn(commits::markCorrupted);
    }

    private static void catchAndWarn(@NotNull ThrowableRunnable<IOException> runnable) {
      try {
        runnable.run();
      }
      catch (IOException e) {
        LOG.warn(e);
      }
    }

    private static void cleanup(@NotNull String logId) {
      StorageId storageId = new StorageId(INDEX, logId, getVersion());
      if (!storageId.cleanupAllStorageFiles()) {
        LOG.error("Could not clean up storage files in " + storageId.subdir() + " starting with " + logId);
      }
    }

    private static int getVersion() {
      return VcsLogStorageImpl.VERSION + VERSION;
    }

    public void unmarkFresh() {
      myIsFresh = false;
    }

    public boolean isFresh() {
      return myIsFresh;
    }

    @Override
    public void dispose() {
    }
  }

  private class MySingleTaskController extends SingleTaskController<IndexingRequest, Void> {
    private static final int LOW_PRIORITY = Thread.MIN_PRIORITY;
    @NotNull private final HeavyAwareExecutor myHeavyAwareExecutor;

    MySingleTaskController(@NotNull Project project, @NotNull Disposable parent) {
      super(project, "index", EmptyConsumer.getInstance(), parent);
      myHeavyAwareExecutor = new HeavyAwareExecutor(project, 50, 100, VcsLogPersistentIndex.this);
    }

    @NotNull
    @Override
    protected SingleTask startNewBackgroundTask() {
      ProgressIndicator indicator = myProgress.createProgressIndicator(false, INDEXING);
      Consumer<ProgressIndicator> task = progressIndicator -> {
        int previousPriority = setMinimumPriority();
        try {
          IndexingRequest request;
          while ((request = popRequest()) != null) {
            try {
              request.run(progressIndicator);
              progressIndicator.checkCanceled();
            }
            catch (ProcessCanceledException reThrown) {
              throw reThrown;
            }
            catch (Throwable t) {
              LOG.error("Error while indexing", t);
            }
          }
        }
        finally {
          taskCompleted(null);
          resetPriority(previousPriority);
        }
      };
      Future<?> future = myHeavyAwareExecutor.executeOutOfHeavyOrPowerSave(task, "Indexing Commit Data", indicator);
      return new SingleTaskImpl(future, indicator);
    }

    public void resetPriority(int previousPriority) {
      if (Thread.currentThread().getPriority() == LOW_PRIORITY) Thread.currentThread().setPriority(previousPriority);
    }

    public int setMinimumPriority() {
      int previousPriority = Thread.currentThread().getPriority();
      try {
        Thread.currentThread().setPriority(LOW_PRIORITY);
      }
      catch (SecurityException e) {
        LOG.debug("Could not set indexing thread priority", e);
      }
      return previousPriority;
    }
  }

  private class IndexingRequest {
    private static final int BATCH_SIZE = 20000;
    private static final int FLUSHED_COMMITS_NUMBER = 15000;
    @NotNull private final VirtualFile myRoot;
    @NotNull private final TIntHashSet myCommits;
    @NotNull private final VcsLogIndexer.PathsEncoder myPathsEncoder;
    private final boolean myFull;

    @NotNull private final AtomicInteger myNewIndexedCommits = new AtomicInteger();
    @NotNull private final AtomicInteger myOldCommits = new AtomicInteger();
    private volatile long myStartTime;

    IndexingRequest(@NotNull VirtualFile root,
                    @NotNull VcsLogIndexer.PathsEncoder encoder,
                    @NotNull TIntHashSet commits,
                    boolean full) {
      myRoot = root;
      myPathsEncoder = encoder;
      myCommits = commits;
      myFull = full;
    }

    public void run(@NotNull ProgressIndicator indicator) {
      if (myBigRepositoriesList.isBig(myRoot)) {
        LOG.info("Indexing repository " + myRoot.getName() + " is skipped since it is too big");
        return;
      }

      indicator.setIndeterminate(false);
      indicator.setFraction(0);

      myStartTime = getCurrentTimeMillis();

      LOG.debug("Indexing " + (myFull ? "full repository" : myCommits.size() + " commits") + " in " + myRoot.getName());

      try {
        try {
          if (myFull) {
            indexAll(indicator);
          }
          else {
            IntStream commits = TroveUtil.stream(myCommits).filter(c -> {
              if (isIndexed(c)) {
                myOldCommits.incrementAndGet();
                return false;
              }
              return true;
            });

            indexOneByOne(commits, indicator);
          }
        }
        catch (ProcessCanceledException e) {
          scheduleReindex();
          throw e;
        }
        catch (VcsException e) {
          LOG.error(e);
          scheduleReindex();
        }
      }
      finally {
        myNumberOfTasks.get(myRoot).decrementAndGet();

        myIndexingTime.get(myRoot).updateAndGet(t -> t + (getCurrentTimeMillis() - myStartTime));
        if (isIndexed(myRoot)) {
          long time = myIndexingTime.get(myRoot).getAndSet(0);
          myIndexCollector.reportIndexingTime(time);
          myListeners.forEach(listener -> listener.indexingFinished(myRoot));
        }

        report();

        flush();
      }
    }

    private long getCurrentTimeMillis() {
      return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    }

    private void report() {
      String formattedTime = StopWatch.formatTime(getCurrentTimeMillis() - myStartTime);
      if (myFull) {
        LOG.debug(formattedTime +
                  " for indexing " +
                  myNewIndexedCommits + " commits in " + myRoot.getName());
      }
      else {
        int leftCommits = myCommits.size() - myNewIndexedCommits.get() - myOldCommits.get();
        String leftCommitsMessage = (leftCommits > 0) ? ". " + leftCommits + " commits left" : "";

        LOG.debug(formattedTime +
                  " for indexing " +
                  myNewIndexedCommits +
                  " new commits out of " +
                  myCommits.size() + " in " + myRoot.getName() + leftCommitsMessage);
      }
    }

    private void scheduleReindex() {
      LOG.debug("Schedule reindexing of " +
                (myCommits.size() - myNewIndexedCommits.get() - myOldCommits.get()) +
                " commits in " +
                myRoot.getName());
      myCommits.forEach(value -> {
        markForIndexing(value, myRoot);
        return true;
      });
      scheduleIndex(false);
    }

    private void indexOneByOne(@NotNull IntStream commits, @NotNull ProgressIndicator indicator) throws VcsException {
      // We pass hashes to VcsLogProvider#readFullDetails in batches
      // in order to avoid allocating too much memory for these hashes
      // a batch of 20k will occupy ~2.4Mb
      TroveUtil.processBatches(commits, BATCH_SIZE, batch -> {
        indicator.checkCanceled();

        List<String> hashes = TroveUtil.map2List(batch, value -> myStorage.getCommitId(value).getHash().asString());
        myIndexers.get(myRoot).readFullDetails(myRoot, hashes, myPathsEncoder, detail -> {
          storeDetail(detail);
          myNewIndexedCommits.incrementAndGet();

          checkRunningTooLong(indicator);
        });

        displayProgress(indicator);
      });
    }

    public void indexAll(@NotNull ProgressIndicator indicator) throws VcsException {
      displayProgress(indicator);

      myIndexers.get(myRoot).readAllFullDetails(myRoot, myPathsEncoder, details -> {
        storeDetail(details);

        if (myNewIndexedCommits.incrementAndGet() % FLUSHED_COMMITS_NUMBER == 0) flush();

        checkRunningTooLong(indicator);
        displayProgress(indicator);
      });
    }

    private void checkRunningTooLong(@NotNull ProgressIndicator indicator) {
      long time = myIndexingTime.get(myRoot).get() + (getCurrentTimeMillis() - myStartTime);
      int limit = myIndexingLimit.get(myRoot).get();
      if (time >= Math.max(limit, 1L) * 60 * 1000 && !myBigRepositoriesList.isBig(myRoot)) {
        LOG.warn("Indexing " + myRoot.getName() + " was cancelled after " + StopWatch.formatTime(time));
        myBigRepositoriesList.addRepository(myRoot);
        myIndexingLimit.get(myRoot).compareAndSet(limit,
                                                  Math.max(limit + getIndexingLimit(),
                                                           (int)((time / (getIndexingLimit() * 60000) + 1) * getIndexingLimit())));
        indicator.cancel();
        showIndexingNotification(limit);
      }
    }

    public void displayProgress(@NotNull ProgressIndicator indicator) {
      indicator.setFraction(((double)myNewIndexedCommits.get() + myOldCommits.get()) / myCommits.size());
    }

    @Override
    public String toString() {
      return "IndexingRequest of " + myCommits.size() + " commits in " + myRoot.getName() + (myFull ? " (full)" : "");
    }

    private void showIndexingNotification(int limitMinutes) {
      myIndexCollector.reportIndexingTooLongNotification();
      AbstractVcs vcs = VcsUtil.findVcsByKey(myProject, myIndexers.get(myRoot).getSupportedVcs());
      String vcsName = vcs != null ? vcs.getDisplayName() : "Vcs";
      Notification notification = VcsNotifier.createNotification(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION, "",
                                                                 vcsName +
                                                                 " Log indexing was paused for '" + myRoot.getName() + "'" +
                                                                 " as it took more than " + formatTime(limitMinutes),
                                                                 NotificationType.INFORMATION, null);
      notification.addAction(NotificationAction.createSimple("Resume", () -> {
        myIndexCollector.reportResumeClick();
        LOG.info("Resuming indexing for " + myRoot.getName());
        if (myBigRepositoriesList.removeRepository(myRoot)) scheduleIndex(false);
      }));
      notification.setContextHelpAction(new DumbAwareAction("Why is it helpful?",
                                                            "Indexing speeds up search and other operations in " +
                                                            vcsName + " Log and in File History." +
                                                            " Old style File History is shown if no index is available.",
                                                            null) {
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
        }
      });
      Disposable disposable = Disposer.newDisposable();
      Disposer.register(VcsLogPersistentIndex.this, disposable);
      myBigRepositoriesList.addListener(() -> {
        if (!myBigRepositoriesList.isBig(myRoot)) {
          notification.expire();
          Disposer.dispose(disposable);
        }
      }, disposable);
      notification.whenExpired(() -> Disposer.dispose(disposable));
      // if our bg thread is cancelled, calling VcsNotifier.getInstance in it will throw PCE
      // so using invokeLater here
      ApplicationManager.getApplication().invokeLater(() -> VcsNotifier.getInstance(myProject).notify(notification));
    }

    @NotNull
    private String formatTime(int timeMinutes) {
      if (timeMinutes > 60) {
        String hours = formatTime(timeMinutes / 60, "hour");
        timeMinutes = timeMinutes % 60;
        if (timeMinutes == 0) return hours;
        return hours + " " + formatTime(timeMinutes, "minute");
      }
      return formatTime(timeMinutes, "minute");
    }

    @NotNull
    private String formatTime(int time, String name) {
      return time + " " + StringUtil.pluralized(name, time);
    }
  }
}
