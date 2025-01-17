// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ide.plugins;

import com.intellij.CommonBundle;
import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.ide.DataManager;
import com.intellij.ide.IdeBundle;
import com.intellij.ide.plugins.sorters.SortByStatusAction;
import com.intellij.ide.ui.search.SearchUtil;
import com.intellij.ide.ui.search.SearchableOptionsRegistrar;
import com.intellij.notification.NotificationListener;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ApplicationNamesInfo;
import com.intellij.openapi.application.ex.ApplicationEx;
import com.intellij.openapi.application.ex.ApplicationInfoEx;
import com.intellij.openapi.application.ex.ApplicationManagerEx;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.OnePixelDivider;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.updateSettings.impl.UpdateChecker;
import com.intellij.openapi.updateSettings.impl.UpdateSettings;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.*;
import com.intellij.ui.border.CustomLineBorder;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.scale.JBUIScale;
import com.intellij.ui.speedSearch.SpeedSearchSupply;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.update.UiNotifyConnector;
import com.intellij.xml.util.XmlStringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.event.HyperlinkEvent;
import javax.swing.event.HyperlinkListener;
import javax.swing.event.TableModelListener;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.HTMLFrameHyperlinkEvent;
import javax.swing.text.html.StyleSheet;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.*;

import static com.intellij.openapi.util.text.StringUtil.isEmptyOrSpaces;

/**
 * @author stathik
 * @author Konstantin Bulenkov
 */
public abstract class PluginManagerMain implements Disposable {
  public static final String JETBRAINS_VENDOR = "JetBrains";

  public static final Logger LOG = Logger.getInstance(PluginManagerMain.class);

  private static final String TEXT_SUFFIX = "</body></html>";
  private static final String HTML_PREFIX = "<a href=\"";
  private static final String HTML_SUFFIX = "</a>";

  private boolean requireShutdown;

  private JPanel myToolbarPanel;
  private JPanel main;

  private JEditorPane myDescriptionTextArea;

  private JPanel myTablePanel;
  protected JPanel myActionsPanel;
  private JPanel myHeader;
  private PluginHeaderPanel myPluginHeaderPanel;
  private JPanel myInfoPanel;
  protected JBLabel myPanelDescription;
  private JBScrollPane myDescriptionScrollPane;


  PluginTableModel pluginsModel;
  protected PluginTable pluginTable;

  private ActionToolbar myActionToolbar;

  protected final MyPluginsFilter myFilter = new MyPluginsFilter();
  private boolean myDisposed;
  private boolean myBusy;

  public static boolean isDevelopedByJetBrains(@NotNull IdeaPluginDescriptor plugin) {
    return isDevelopedByJetBrains(plugin.getVendor());
  }

  public static boolean isDevelopedByJetBrains(@Nullable String vendorString) {
    if (vendorString == null) return false;
    for (String vendor : StringUtil.split(vendorString, ",")) {
      if (vendor.trim().equals(JETBRAINS_VENDOR)) {
        return true;
      }
    }
    return false;
  }

  protected void init() {
    Color background = UIUtil.getTextFieldBackground();
    GuiUtils.replaceJSplitPaneWithIDEASplitter(main, true);
    HTMLEditorKit kit = UIUtil.getHTMLEditorKit();
    StyleSheet sheet = kit.getStyleSheet();
    sheet.addRule("ul {margin-left: 16px}"); // list-style-type: none;
    myDescriptionTextArea.setEditorKit(kit);
    myDescriptionTextArea.setEditable(false);
    myDescriptionTextArea.addHyperlinkListener(new MyHyperlinkListener());

    JScrollPane installedScrollPane = createTable();
    installedScrollPane.setBorder(JBUI.Borders.customLine(OnePixelDivider.BACKGROUND, 1, 1, 1, 0));
    myPluginHeaderPanel = new PluginHeaderPanel(this);
    myPluginHeaderPanel.getPanel().setBackground(background);
    myPluginHeaderPanel.getPanel().setOpaque(true);

    myHeader.add(myPluginHeaderPanel.getPanel(), BorderLayout.CENTER);
    installTableActions();

    myTablePanel.add(installedScrollPane, BorderLayout.CENTER);
    UIUtil.applyStyle(UIUtil.ComponentStyle.SMALL, myPanelDescription);
    myPanelDescription.setBorder(JBUI.Borders.emptyLeft(7));

    JPanel header = new JPanel(new BorderLayout()) {
      @Override
      protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Color bg = UIUtil.getTableBackground(false, true);
        ((Graphics2D)g).setPaint(new GradientPaint(0, 0, ColorUtil.shift(bg, 1.4), 0, getHeight(), ColorUtil.shift(bg, 0.9)));
        g.fillRect(0,0, getWidth(), getHeight());
      }
    };
    header.setBorder(new CustomLineBorder(1, 1, 0, 0));
    JLabel mySortLabel = new JLabel();
    mySortLabel.setForeground(UIUtil.getLabelDisabledForeground());
    mySortLabel.setBorder(JBUI.Borders.empty(1, 1, 1, 5));
    mySortLabel.setIcon(AllIcons.General.ArrowDown);
    mySortLabel.setHorizontalTextPosition(SwingConstants.LEADING);
    header.add(mySortLabel, BorderLayout.EAST);
    myTablePanel.add(header, BorderLayout.NORTH);
    myToolbarPanel.setLayout(new BorderLayout());
    myActionToolbar = ActionManager.getInstance().createActionToolbar("PluginManager", getActionGroup(true), true);
    JComponent component = myActionToolbar.getComponent();
    component.setBorder(JBUI.Borders.emptyLeft(UIUtil.DEFAULT_HGAP));
    myToolbarPanel.add(component, BorderLayout.CENTER);
    myToolbarPanel.add(myFilter, BorderLayout.WEST);
    new ClickListener() {
      @Override
      public boolean onClick(@NotNull MouseEvent event, int clickCount) {
        JBPopupFactory.getInstance().createActionGroupPopup("Sort by:", createSortersGroup(), DataManager.getInstance().getDataContext(pluginTable),
                                                            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true).showUnderneathOf(mySortLabel);
        return true;
      }
    }.installOn(mySortLabel);
    TableModelListener modelListener = __ -> {
      String text = "Sort by:";
      if (pluginsModel.isSortByStatus()) {
        text += " status,";
      }
      if (pluginsModel.isSortByRating()) {
        text += " rating,";
      }
      if (pluginsModel.isSortByDownloads()) {
        text += " downloads,";
      }
      if (pluginsModel.isSortByUpdated()) {
        text += " updated,";
      }
      text += " name";
      mySortLabel.setText(text);
    };
    pluginTable.getModel().addTableModelListener(modelListener);
    modelListener.tableChanged(null);

    myDescriptionScrollPane.setBackground(background);
    Border border = JBUI.Borders.customLine(OnePixelDivider.BACKGROUND, 1, 0, 1, 1);
    myInfoPanel.setBorder(BorderFactory.createCompoundBorder(border, JBUI.Borders.emptyLeft(5)));
    myInfoPanel.setBackground(background);
    myHeader.setBackground(background);
  }

  protected abstract JScrollPane createTable();

  @Override
  public void dispose() {
    myDisposed = true;
  }

  public boolean isDisposed() {
    return myDisposed;
  }

  public void filter(String filter) {
    myFilter.setSelectedItem(filter);
  }

  public void reset() {
    UiNotifyConnector.doWhenFirstShown(getPluginTable(), () -> {
      requireShutdown = false;
      TableUtil.ensureSelectionExists(getPluginTable());
    });
  }

  public PluginTable getPluginTable() {
    return pluginTable;
  }

  private static String getTextPrefix() {
    int fontSize = JBUIScale.scale(12);
    int m1 = JBUIScale.scale(2);
    int m2 = JBUIScale.scale(5);
    return String.format(
           "<html><head>" +
           "    <style type=\"text/css\">" +
           "        p {" +
           "            font-family: Arial,serif; font-size: %dpt; margin: %dpx %dpx" +
           "        }" +
           "    </style>" +
           "</head><body style=\"font-family: Arial,serif; font-size: %dpt; margin: %dpx %dpx;\">",
           fontSize, m1, m1, fontSize, m2, m2);
  }

  public PluginTableModel getPluginsModel() {
    return pluginsModel;
  }

  protected void installTableActions() {
    pluginTable.getSelectionModel().addListSelectionListener(__ -> refresh());

    PopupHandler.installUnknownPopupHandler(pluginTable, getActionGroup(false), ActionManager.getInstance());

    new MySpeedSearchBar(pluginTable);
  }

  public void refresh() {
    IdeaPluginDescriptor[] descriptors = pluginTable.getSelectedObjects();
    IdeaPluginDescriptor plugin = descriptors != null && descriptors.length == 1 ? descriptors[0] : null;
    pluginInfoUpdate(plugin, myFilter.getFilter(), myDescriptionTextArea, myPluginHeaderPanel);
    myActionToolbar.updateActionsImmediately();
    JComponent parent = (JComponent)myHeader.getParent();
    parent.revalidate();
    parent.repaint();
  }

  void setRequireShutdown(boolean val) {
    requireShutdown |= val;
  }

  @NotNull
  List<IdeaPluginDescriptorImpl> getDependentList(IdeaPluginDescriptorImpl pluginDescriptor) {
    return pluginsModel.dependent(pluginDescriptor);
  }

  void modifyPluginsList(@NotNull List<? extends IdeaPluginDescriptor> list) {
    IdeaPluginDescriptor[] selected = pluginTable.getSelectedObjects();
    pluginsModel.updatePluginsList(list);
    pluginsModel.filter(StringUtil.toLowerCase(myFilter.getFilter()));
    if (selected != null) {
      select(selected);
    }
  }

  @NotNull
  protected abstract ActionGroup getActionGroup(boolean inToolbar);

  protected abstract PluginManagerMain getAvailable();
  protected abstract PluginManagerMain getInstalled();

  public JPanel getMainPanel() {
    return main;
  }

  protected boolean acceptHost(String host) {
    return true;
  }

  /**
   * Start a new thread which downloads new list of plugins from the site in
   * the background and updates a list of plugins in the table.
   */
  private void loadPluginsFromHostInBackground() {
    setDownloadStatus(true);

    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      List<IdeaPluginDescriptor> list = new ArrayList<>();
      Map<String, String> errors = new LinkedHashMap<>();
      ProgressIndicator indicator = new EmptyProgressIndicator();

      List<String> hosts = RepositoryHelper.getPluginHosts();
      Set<PluginId> unique = new HashSet<>();
      for (String host : hosts) {
        try {
          if (host == null || acceptHost(host)) {
            List<IdeaPluginDescriptor> plugins = RepositoryHelper.loadPlugins(host, indicator);
            for (IdeaPluginDescriptor plugin : plugins) {
              if (unique.add(plugin.getPluginId())) {
                list.add(plugin);
              }
            }
          }
        }
        catch (FileNotFoundException e) {
          LOG.info(host, e);
        }
        catch (IOException e) {
          LOG.info(host, e);
          if (host != ApplicationInfoEx.getInstanceEx().getBuiltinPluginsUrl()) {
            errors.put(host, String.format("'%s' for '%s'", e.getMessage(), host));
          }
        }
      }

      UIUtil.invokeLaterIfNeeded(() -> {
        setDownloadStatus(false);

        if (!list.isEmpty()) {
          InstalledPluginsState state = InstalledPluginsState.getInstance();
          for (IdeaPluginDescriptor descriptor : list) {
            state.onDescriptorDownload(descriptor);
          }

          modifyPluginsList(list);
          propagateUpdates(list);
        }

        if (!errors.isEmpty()) {
          String message = IdeBundle.message("error.list.of.plugins.was.not.loaded",
                                             StringUtil.join(errors.keySet(), ", "),
                                             StringUtil.join(errors.values(), ",\n"));
          String title = IdeBundle.message("title.plugins");
          String ok = CommonBundle.message("button.retry");
          String cancel = CommonBundle.getCancelButtonText();
          if (Messages.showOkCancelDialog(message, title, ok, cancel, Messages.getErrorIcon()) == Messages.OK) {
            loadPluginsFromHostInBackground();
          }
        }
      });
    });
  }

  protected abstract void propagateUpdates(List<? extends IdeaPluginDescriptor> list);

  private void setDownloadStatus(boolean status) {
    pluginTable.setPaintBusy(status);
    myBusy = status;
  }

  void loadAvailablePlugins() {
    try {
      //  If we already have a file with downloaded plugins from the last time,
      //  then read it, load into the list and start the updating process.
      //  Otherwise just start the process of loading the list and save it
      //  into the persistent config file for later reading.
      List<IdeaPluginDescriptor> list = RepositoryHelper.loadCachedPlugins();
      if (list != null) {
        modifyPluginsList(list);
      }
    }
    catch (Exception ex) {
      //  Nothing to do, just ignore - if nothing can be read from the local
      //  file just start downloading of plugins' list from the site.
    }
    loadPluginsFromHostInBackground();
  }

  /**
   * @deprecated use {@link #downloadPlugins(List, List, Runnable, PluginEnabler, Runnable)} instead
   */
  @Deprecated
  public static boolean downloadPlugins(List<PluginNode> plugins,
                                        List<? extends PluginId> allPlugins,
                                        Runnable onSuccess,
                                        @Nullable Runnable cleanup) throws IOException {
    return downloadPlugins(plugins,
                           ContainerUtil.map(allPlugins, p -> new PluginNode(p, p.getIdString(), "-1")),
                           onSuccess,
                           new PluginEnabler.HEADLESS(),
                           cleanup);
  }

  public static boolean downloadPlugins(List<PluginNode> plugins,
                                        List<? extends IdeaPluginDescriptor> allPlugins,
                                        Runnable onSuccess,
                                        PluginEnabler pluginEnabler,
                                        @Nullable Runnable cleanup) throws IOException {
    boolean[] result = new boolean[1];
    try {
      ProgressManager.getInstance().run(new Task.Backgroundable(null, IdeBundle.message("progress.download.plugins"), true, PluginManagerUISettings.getInstance()) {
        @Override
        public void run(@NotNull ProgressIndicator indicator) {
          try {
            if (PluginInstaller.prepareToInstall(plugins, allPlugins, pluginEnabler, indicator)) {
              ApplicationManager.getApplication().invokeLater(onSuccess);
              result[0] = true;
            }
          }
          finally {
            if (cleanup != null) {
              ApplicationManager.getApplication().invokeLater(cleanup);
            }
          }
        }
      });
    }
    catch (RuntimeException e) {
      if (e.getCause() != null && e.getCause() instanceof IOException) {
        throw (IOException)e.getCause();
      }
      else {
        throw e;
      }
    }
    return result[0];
  }

  boolean isRequireShutdown() {
    return requireShutdown;
  }

  public void ignoreChanges() {
    requireShutdown = false;
  }

  public static void pluginInfoUpdate(IdeaPluginDescriptor plugin,
                                      @Nullable String filter,
                                      @NotNull JEditorPane descriptionTextArea,
                                      @NotNull PluginHeaderPanel header) {
    if (plugin == null) {
      setTextValue(null, filter, descriptionTextArea);
      header.getPanel().setVisible(false);
      return;
    }
    StringBuilder sb = new StringBuilder();
    header.setPlugin(plugin);
    String description = plugin.getDescription();
    if (!isEmptyOrSpaces(description)) {
      sb.append(description);
    }

    String changeNotes = plugin.getChangeNotes();
    if (!isEmptyOrSpaces(changeNotes)) {
      sb.append("<h4>Change Notes</h4>");
      sb.append(changeNotes);
    }

    if (!plugin.isBundled()) {
      String vendor = plugin.getVendor();
      String vendorEmail = plugin.getVendorEmail();
      String vendorUrl = plugin.getVendorUrl();
      if (!isEmptyOrSpaces(vendor) || !isEmptyOrSpaces(vendorEmail) || !isEmptyOrSpaces(vendorUrl)) {
        sb.append("<h4>Vendor</h4>");

        if (!isEmptyOrSpaces(vendor)) {
          sb.append(vendor);
        }
        if (!isEmptyOrSpaces(vendorUrl)) {
          sb.append("<br>").append(composeHref(vendorUrl));
        }
        if (!isEmptyOrSpaces(vendorEmail)) {
          sb.append("<br>")
            .append(HTML_PREFIX)
            .append("mailto:").append(vendorEmail)
            .append("\">").append(vendorEmail).append(HTML_SUFFIX);
        }
      }

      String pluginDescriptorUrl = plugin.getUrl();
      if (!isEmptyOrSpaces(pluginDescriptorUrl)) {
        sb.append("<h4>Plugin homepage</h4>").append(composeHref(pluginDescriptorUrl));
      }

      String size = plugin instanceof PluginNode ? ((PluginNode)plugin).getSize() : null;
      if (!isEmptyOrSpaces(size)) {
        sb.append("<h4>Size</h4>").append(PluginManagerColumnInfo.getFormattedSize(size));
      }
    }

    setTextValue(sb, filter, descriptionTextArea);
  }

  private static void setTextValue(@Nullable StringBuilder text, @Nullable String filter, JEditorPane pane) {
    if (text != null) {
      text.insert(0, getTextPrefix());
      text.append(TEXT_SUFFIX);
      pane.setText(SearchUtil.markup(text.toString(), filter).trim());
      pane.setCaretPosition(0);
    }
    else {
      pane.setText(getTextPrefix() + TEXT_SUFFIX);
    }
  }

  private static String composeHref(String vendorUrl) {
    return HTML_PREFIX + vendorUrl + "\">" + vendorUrl + HTML_SUFFIX;
  }

  public boolean isModified() {
    return requireShutdown;
  }

  public String apply() {
    String applyMessage = canApply();
    if (applyMessage != null) return applyMessage;
    setRequireShutdown(true);
    return null;
  }

  @Nullable
  protected String canApply() {
    return null;
  }

  public static class MyHyperlinkListener implements HyperlinkListener {
    @Override
    public void hyperlinkUpdate(HyperlinkEvent e) {
      if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
        JEditorPane pane = (JEditorPane)e.getSource();
        if (e instanceof HTMLFrameHyperlinkEvent) {
          HTMLFrameHyperlinkEvent evt = (HTMLFrameHyperlinkEvent)e;
          HTMLDocument doc = (HTMLDocument)pane.getDocument();
          doc.processHTMLFrameHyperlinkEvent(evt);
        }
        else {
          URL url = e.getURL();
          if (url != null) {
            BrowserUtil.browse(url);
          }
        }
      }
    }
  }

  private static class MySpeedSearchBar extends SpeedSearchBase<PluginTable> {
    MySpeedSearchBar(PluginTable cmp) {
      super(cmp);
    }

    @Override
    protected int convertIndexToModel(int viewIndex) {
      return getComponent().convertRowIndexToModel(viewIndex);
    }

    @Override
    public int getSelectedIndex() {
      return myComponent.getSelectedRow();
    }

    @NotNull
    @Override
    public Object[] getAllElements() {
      return myComponent.getElements();
    }

    @Override
    public String getElementText(Object element) {
      return ((IdeaPluginDescriptor)element).getName();
    }

    @Override
    public void selectElement(Object element, String selectedText) {
      for (int i = 0; i < myComponent.getRowCount(); i++) {
        if (myComponent.getObjectAt(i).getName().equals(((IdeaPluginDescriptor)element).getName())) {
          myComponent.setRowSelectionInterval(i, i);
          TableUtil.scrollSelectionToVisible(myComponent);
          break;
        }
      }
    }
  }

  public void select(IdeaPluginDescriptor... descriptors) {
    pluginTable.select(descriptors);
  }

  public static boolean isAccepted(@Nullable String filter, @NotNull Set<String> search, @NotNull IdeaPluginDescriptor descriptor) {
    if (StringUtil.isEmpty(filter)) return true;
    if (StringUtil.containsIgnoreCase(descriptor.getName(), filter) || isAccepted(search, filter, descriptor.getName())) return true;
    if (isAccepted(search, filter, descriptor.getDescription())) return true;
    String category = descriptor.getCategory();
    return category != null && (StringUtil.containsIgnoreCase(category, filter) || isAccepted(search, filter, category));
  }

  public static boolean isAccepted(@NotNull Set<String> search, @NotNull String filter, @Nullable String description) {
    if (StringUtil.isEmpty(description)) return false;
    if (filter.length() <= 2) return false;
    Set<String> words = SearchableOptionsRegistrar.getInstance().getProcessedWords(description);
    if (words.contains(filter)) return true;
    if (search.isEmpty()) return false;
    Set<String> descriptionSet = new HashSet<>(search);
    descriptionSet.removeAll(words);
    return descriptionSet.isEmpty();
  }

  public static boolean suggestToEnableInstalledDependantPlugins(PluginEnabler pluginEnabler,
                                                                 List<? extends PluginNode> list) {
    Set<IdeaPluginDescriptor> disabled = new HashSet<>();
    Set<IdeaPluginDescriptor> disabledDependants = new HashSet<>();
    for (PluginNode node : list) {
      PluginId pluginId = node.getPluginId();
      if (pluginEnabler.isDisabled(pluginId)) {
        disabled.add(node);
      }
      List<PluginId> depends = node.getDepends();
      if (depends != null) {
        Set<PluginId> optionalDeps = new HashSet<>(Arrays.asList(node.getOptionalDependentPluginIds()));
        for (PluginId dependantId : depends) {
          if (optionalDeps.contains(dependantId)) continue;
          IdeaPluginDescriptor pluginDescriptor = PluginManager.getPlugin(dependantId);
          if (pluginDescriptor != null && pluginEnabler.isDisabled(dependantId)) {
            disabledDependants.add(pluginDescriptor);
          }
        }
      }
    }

    if (!disabled.isEmpty() || !disabledDependants.isEmpty()) {
      String message = "";
      if (disabled.size() == 1) {
        message += "Updated plugin '" + disabled.iterator().next().getName() + "' is disabled.";
      }
      else if (!disabled.isEmpty()) {
        message += "Updated plugins " + StringUtil.join(disabled, pluginDescriptor -> pluginDescriptor.getName(), ", ") + " are disabled.";
      }

      if (!disabledDependants.isEmpty()) {
        message += "<br>";
        message += "Updated plugin" + (list.size() > 1 ? "s depend " : " depends ") + "on disabled";
        if (disabledDependants.size() == 1) {
          message += " plugin '" + disabledDependants.iterator().next().getName() + "'.";
        }
        else {
          message += " plugins " + StringUtil.join(disabledDependants, pluginDescriptor -> pluginDescriptor.getName(), ", ") + ".";
        }
      }
      message += " Disabled plugins " + (disabled.isEmpty() ? "and plugins which depend on disabled " :"") + "won't be activated after restart.";

      int result;
      if (!disabled.isEmpty() && !disabledDependants.isEmpty()) {
        result =
          Messages.showYesNoCancelDialog(XmlStringUtil.wrapInHtml(message), "Dependent Plugins Found", "Enable all",
                                         "Enable updated plugin" + (disabled.size() > 1 ? "s" : ""), CommonBundle.getCancelButtonText(),
                                         Messages.getQuestionIcon());
        if (result == Messages.CANCEL) return false;
      }
      else {
        message += "<br>Would you like to enable ";
        if (!disabled.isEmpty()) {
          message += "updated plugin" + (disabled.size() > 1 ? "s" : "");
        }
        else {
          message += "plugin " +
                     StringUtil.pluralized("dependency", disabledDependants.size());
        }
        message += "?";
        result = Messages.showYesNoDialog(XmlStringUtil.wrapInHtml(message), "Dependent Plugins Found", Messages.getQuestionIcon());
        if (result == Messages.NO) return false;
      }

      if (result == Messages.YES) {
        disabled.addAll(disabledDependants);
        pluginEnabler.enablePlugins(disabled);
      }
      else if (result == Messages.NO && !disabled.isEmpty()) {
        pluginEnabler.enablePlugins(disabled);
      }
      return true;
    }
    return false;
  }

  public interface PluginEnabler {
    void enablePlugins(Set<? extends IdeaPluginDescriptor> disabled);
    void disablePlugins(Set<? extends IdeaPluginDescriptor> disabled);

    boolean isDisabled(PluginId pluginId);

    class HEADLESS implements PluginEnabler {
      @Override
      public void enablePlugins(Set<? extends IdeaPluginDescriptor> disabled) {
        for (IdeaPluginDescriptor descriptor : disabled) {
          PluginManagerCore.enablePlugin(descriptor.getPluginId().getIdString());
        }
      }

      @Override
      public void disablePlugins(Set<? extends IdeaPluginDescriptor> disabled) {
        for (IdeaPluginDescriptor descriptor : disabled) {
          PluginManagerCore.disablePlugin(descriptor.getPluginId().getIdString());
        }
      }

      @Override
      public boolean isDisabled(PluginId pluginId) {
        return isDisabled(pluginId.getIdString());
      }

      public boolean isDisabled(@NotNull String pluginId) {
        return PluginManagerCore.isDisabled(pluginId);
      }
    }

    class UI implements PluginEnabler {
      @NotNull
      private final InstalledPluginsTableModel pluginsModel;

      public UI(@NotNull InstalledPluginsTableModel model) {
        pluginsModel = model;
      }

      @Override
      public void enablePlugins(Set<? extends IdeaPluginDescriptor> disabled) {
        pluginsModel.enableRows(disabled.toArray(new IdeaPluginDescriptor[0]), true);
      }

      @Override
      public void disablePlugins(Set<? extends IdeaPluginDescriptor> disabled) {
        pluginsModel.enableRows(disabled.toArray(new IdeaPluginDescriptor[0]), false);
      }

      @Override
      public boolean isDisabled(PluginId pluginId) {
        return pluginsModel.isDisabled(pluginId);
      }
    }
  }

  public static void notifyPluginsUpdated(@Nullable Project project) {
    ApplicationEx app = ApplicationManagerEx.getApplicationEx();
    String title = IdeBundle.message("update.notifications.title");
    String action = IdeBundle.message(app.isRestartCapable() ? "ide.restart.action" : "ide.shutdown.action");
    String message = IdeBundle.message("ide.restart.required.notification", action, ApplicationNamesInfo.getInstance().getFullProductName());
    NotificationListener listener = (notification, __) -> {
      notification.expire();
      app.restart(true);
    };
    UpdateChecker.NOTIFICATIONS.createNotification(title, XmlStringUtil.wrapInHtml(message), NotificationType.INFORMATION, listener).notify(project);
  }

  public static boolean checkThirdPartyPluginsAllowed(Iterable<? extends IdeaPluginDescriptor> descriptors) {
    UpdateSettings updateSettings = UpdateSettings.getInstance();

    if (updateSettings.isThirdPartyPluginsAllowed()) {
      return true;
    }

    for (IdeaPluginDescriptor descriptor : descriptors) {
      if (!isDevelopedByJetBrains(descriptor)) {
        String title = IdeBundle.message("third.party.plugins.privacy.note.title");
        String message = IdeBundle.message("third.party.plugins.privacy.note.message");
        String yesText = IdeBundle.message("third.party.plugins.privacy.note.yes");
        String noText = IdeBundle.message("third.party.plugins.privacy.note.no");
        if (Messages.showYesNoDialog(message, title, yesText, noText, Messages.getWarningIcon()) == Messages.YES) {
          updateSettings.setThirdPartyPluginsAllowed(true);
          return true;
        } else {
          return false;
        }
      }
    }

    return true;
  }

  class MyPluginsFilter extends FilterComponent {
    MyPluginsFilter() {
      super("PLUGIN_FILTER", 5);
    }

    @Override
    public void filter() {
      getPluginTable().putClientProperty(SpeedSearchSupply.SEARCH_QUERY_KEY, getFilter());
      pluginsModel.filter(StringUtil.toLowerCase(getFilter()));
      TableUtil.ensureSelectionExists(getPluginTable());
    }
  }

  protected class RefreshAction extends DumbAwareAction {
    public RefreshAction() {
      super("Reload List of Plugins", "Reload list of plugins", AllIcons.Actions.Refresh);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      loadAvailablePlugins();
      myFilter.setFilter("");
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      e.getPresentation().setEnabled(!myBusy);
    }
  }

  protected DefaultActionGroup createSortersGroup() {
    DefaultActionGroup group = new DefaultActionGroup("Sort by", true);
    group.addAction(new SortByStatusAction(pluginTable, pluginsModel));
    return group;
  }
}