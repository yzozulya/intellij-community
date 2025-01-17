// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.yaml.folding;

import com.intellij.lang.ASTNode;
import com.intellij.lang.folding.CustomFoldingBuilder;
import com.intellij.lang.folding.FoldingDescriptor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiElement;
import com.intellij.psi.impl.source.SourceTreeToPsiMap;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.yaml.psi.*;
import org.jetbrains.yaml.psi.impl.YAMLArrayImpl;
import org.jetbrains.yaml.psi.impl.YAMLBlockMappingImpl;
import org.jetbrains.yaml.psi.impl.YAMLBlockSequenceImpl;
import org.jetbrains.yaml.psi.impl.YAMLHashImpl;

import java.util.List;

/**
 * @author oleg
 */
public class YAMLFoldingBuilder extends CustomFoldingBuilder {

  private static final int PLACEHOLDER_LEN = 20;

  @Override
  protected void buildLanguageFoldRegions(@NotNull List<FoldingDescriptor> descriptors,
                                          @NotNull PsiElement root,
                                          @NotNull Document document,
                                          boolean quick) {
    collectDescriptors(root, descriptors);
  }

  private static void collectDescriptors(@NotNull final PsiElement element, @NotNull final List<? super FoldingDescriptor> descriptors) {
    TextRange nodeTextRange = element.getTextRange();
    if (nodeTextRange.getLength() < 2) {
      return;
    }

    if (element instanceof YAMLDocument) {
      if (PsiTreeUtil.findChildrenOfAnyType(element.getParent(), YAMLDocument.class).size() > 1) {
        descriptors.add(new FoldingDescriptor(element, nodeTextRange));
      }
    }
    else if (element instanceof YAMLScalar
             ||
             element instanceof YAMLKeyValue && ((YAMLKeyValue)element).getValue() instanceof YAMLCompoundValue
             ||
             element instanceof YAMLSequenceItem && ((YAMLSequenceItem)element).getValue() instanceof YAMLCompoundValue) {
      descriptors.add(new FoldingDescriptor(element, nodeTextRange));
    }

    for (PsiElement child : element.getChildren()) {
      collectDescriptors(child, descriptors);
    }
  }

  @Override
  @Nullable
  protected String getLanguagePlaceholderText(@NotNull ASTNode node, @NotNull TextRange range) {
    return getPlaceholderText(SourceTreeToPsiMap.treeElementToPsi(node));
  }

  @NotNull
  private static String getPlaceholderText(@Nullable PsiElement psiElement) {

    if (psiElement instanceof YAMLDocument) {
      return "---";
    }
    else if (psiElement instanceof YAMLScalar) {
      return normalizePlaceHolderText(((YAMLScalar)psiElement).getTextValue());
    }
    else if (psiElement instanceof YAMLSequence) {
      final int size = ((YAMLSequence)psiElement).getItems().size();
      final String placeholder = size + " " + StringUtil.pluralized("item", size);
      if (psiElement instanceof YAMLArrayImpl) {
        return "[" + placeholder + "]";
      }
      else if (psiElement instanceof YAMLBlockSequenceImpl) {
        return "<" + placeholder + ">";
      }
    }
    else if (psiElement instanceof YAMLMapping) {
      final int size = ((YAMLMapping)psiElement).getKeyValues().size();
      final String placeholder = size + " " + StringUtil.pluralized("key", size);
      if (psiElement instanceof YAMLHashImpl) {
        return "{" + placeholder + "}";
      }
      else if (psiElement instanceof YAMLBlockMappingImpl) {
        return "<" + placeholder + ">";
      }
    }
    else if (psiElement instanceof YAMLKeyValue) {
      return normalizePlaceHolderText(((YAMLKeyValue)psiElement).getKeyText())
             + ": "
             + getPlaceholderText(((YAMLKeyValue)psiElement).getValue());
    }
    else if (psiElement instanceof YAMLSequenceItem) {
      return "- "
             + getPlaceholderText(((YAMLSequenceItem)psiElement).getValue());
    }
    return "...";
  }

  @Override
  protected boolean isRegionCollapsedByDefault(@NotNull ASTNode node) {
    return false;
  }

  private static String normalizePlaceHolderText(@Nullable String text) {
    if (text == null) {
      return null;
    }

    if (text.length() <= PLACEHOLDER_LEN) {
      return text;
    }
    return StringUtil.trimMiddle(text, PLACEHOLDER_LEN);
  }
}
