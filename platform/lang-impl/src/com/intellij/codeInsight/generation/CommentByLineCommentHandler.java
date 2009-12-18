/*
 * Copyright 2000-2009 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intellij.codeInsight.generation;

import com.intellij.codeInsight.CodeInsightActionHandler;
import com.intellij.codeInsight.CommentUtil;
import com.intellij.featureStatistics.FeatureUsageTracker;
import com.intellij.ide.highlighter.custom.SyntaxTable;
import com.intellij.lang.Commenter;
import com.intellij.lang.Language;
import com.intellij.lang.LanguageCommenters;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.actionSystem.ex.ActionManagerEx;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.impl.AbstractFileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.CodeStyleSettingsManager;
import com.intellij.psi.codeStyle.Indent;
import com.intellij.psi.util.PsiUtilBase;
import com.intellij.util.StringBuilderSpinAllocator;
import com.intellij.util.containers.IntArrayList;
import com.intellij.util.text.CharArrayUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class CommentByLineCommentHandler implements CodeInsightActionHandler {
  private Project myProject;
  private PsiFile myFile;
  private Document myDocument;
  private Editor myEditor;
  private int myStartOffset;
  private int myEndOffset;
  private int myStartLine;
  private int myEndLine;
  private int[] myStartOffsets;
  private int[] myEndOffsets;
  private Commenter[] myCommenters;
  private CodeStyleManager myCodeStyleManager;

  public void invoke(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
    myProject = project;
    myFile = file.getViewProvider().getPsi(file.getViewProvider().getBaseLanguage());
    myDocument = editor.getDocument();
    myEditor = editor;

    if (!FileDocumentManager.getInstance().requestWriting(myDocument, project)) {
      return;
    }

    PsiDocumentManager.getInstance(project).commitDocument(myDocument);

    FeatureUsageTracker.getInstance().triggerFeatureUsed("codeassists.comment.line");

    //myCodeInsightSettings = (CodeInsightSettings)ApplicationManager.getApplication().getComponent(CodeInsightSettings.class);
    myCodeStyleManager = CodeStyleManager.getInstance(myProject);

    final SelectionModel selectionModel = editor.getSelectionModel();

    boolean hasSelection = selectionModel.hasSelection();
    myStartOffset = selectionModel.getSelectionStart();
    myEndOffset = selectionModel.getSelectionEnd();

    if (myDocument.getTextLength() == 0) return;

    while (true) {
      int lastLineEnd = myDocument.getLineEndOffset(myDocument.getLineNumber(myEndOffset));
      FoldRegion collapsedAt = editor.getFoldingModel().getCollapsedRegionAtOffset(lastLineEnd);
      if (collapsedAt != null) {
        final int endOffset = collapsedAt.getEndOffset();
        if (endOffset <= myEndOffset) {
          break;
        }
        myEndOffset = endOffset;
      } else {
        break;
      }
    }

    boolean wholeLinesSelected = !hasSelection || (
      myStartOffset == myDocument.getLineStartOffset(myDocument.getLineNumber(myStartOffset)) &&
      myEndOffset == myDocument.getLineEndOffset(myDocument.getLineNumber(myEndOffset - 1)) + 1);

    boolean startingNewLineComment = !hasSelection && isLineEmpty(myDocument.getLineNumber(myStartOffset)) && !Comparing
      .equal(IdeActions.ACTION_COMMENT_LINE, ActionManagerEx.getInstanceEx().getPrevPreformedActionId());
    doComment();

    if (startingNewLineComment) {
      final Commenter commenter = myCommenters[0];
      if (commenter != null) {
        String prefix = commenter.getLineCommentPrefix();
        if (prefix == null) prefix = commenter.getBlockCommentPrefix();
        int lineStart = myDocument.getLineStartOffset(myStartLine);
        lineStart = CharArrayUtil.shiftForward(myDocument.getCharsSequence(), lineStart, " \t");
        lineStart += prefix.length();
        if (lineStart < myDocument.getTextLength() && myDocument.getCharsSequence().charAt(lineStart) == ' ') lineStart++;
        editor.getCaretModel().moveToOffset(lineStart);
        editor.getScrollingModel().scrollToCaret(ScrollType.RELATIVE);
      }
    }
    else {
      if (!hasSelection) {
        editor.getCaretModel().moveCaretRelatively(0, 1, false, false, true);
      }
      else {
        if (wholeLinesSelected) {
          selectionModel.setSelection(myStartOffset, selectionModel.getSelectionEnd());
        }
      }
    }
  }

  private boolean isLineEmpty(final int line) {
    final CharSequence chars = myDocument.getCharsSequence();
    int start = myDocument.getLineStartOffset(line);
    int end = Math.min(myDocument.getLineEndOffset(line), myDocument.getTextLength() - 1);
    for (int i = start; i <= end; i++) {
      if (!Character.isWhitespace(chars.charAt(i))) return false;
    }
    return true;
  }

  public boolean startInWriteAction() {
    return true;
  }

  private void doComment() {
    myStartLine = myDocument.getLineNumber(myStartOffset);
    myEndLine = myDocument.getLineNumber(myEndOffset);

    if (myEndLine > myStartLine && myDocument.getLineStartOffset(myEndLine) == myEndOffset) {
      myEndLine--;
    }

    myStartOffsets = new int[myEndLine - myStartLine + 1];
    myEndOffsets = new int[myEndLine - myStartLine + 1];
    myCommenters = new Commenter[myEndLine - myStartLine + 1];
    boolean allLineCommented = true;
    CharSequence chars = myDocument.getCharsSequence();

    boolean singleline = myStartLine == myEndLine;
    int offset = myDocument.getLineStartOffset(myStartLine);
    offset = CharArrayUtil.shiftForward(myDocument.getCharsSequence(), offset, " \t");
    final Language languageSuitableForCompleteFragment = PsiUtilBase.reallyEvaluateLanguageInRange(offset, CharArrayUtil.shiftBackward(
      myDocument.getCharsSequence(), myDocument.getLineEndOffset(myEndLine), " \t\n"), myFile);

    Commenter blockSuitableCommenter = languageSuitableForCompleteFragment == null ? LanguageCommenters.INSTANCE.forLanguage(myFile.getLanguage()) : null;
    if (blockSuitableCommenter == null && myFile.getFileType() instanceof AbstractFileType) {
      blockSuitableCommenter = new Commenter() {
        final SyntaxTable mySyntaxTable = ((AbstractFileType)myFile.getFileType()).getSyntaxTable();
        @Nullable
        public String getLineCommentPrefix() {
          return mySyntaxTable.getLineComment();
        }

        @Nullable
        public String getBlockCommentPrefix() {
          return mySyntaxTable.getStartComment();
        }

        @Nullable
        public String getBlockCommentSuffix() {
          return mySyntaxTable.getEndComment();
        }

        public String getCommentedBlockCommentPrefix() {
          return null;
        }

        public String getCommentedBlockCommentSuffix() {
          return null;
        }
      };
    }

    for (int line = myStartLine; line <= myEndLine; line++) {
      final Commenter commenter = blockSuitableCommenter != null ? blockSuitableCommenter : findCommenter(line);
      if (commenter == null) return;

      if (commenter.getLineCommentPrefix() == null &&
          (commenter.getBlockCommentPrefix() == null || commenter.getBlockCommentSuffix() == null)) {
        return;
      }
      myCommenters[line - myStartLine] = commenter;
      if (!isLineCommented(line, chars, commenter) && (singleline || !isLineEmpty(line))) {
        allLineCommented = false;
        break;
      }
    }

    if (!allLineCommented) {
      if (CodeStyleSettingsManager.getSettings(myProject).LINE_COMMENT_AT_FIRST_COLUMN) {
        doDefaultCommenting(blockSuitableCommenter);
      }
      else {
        doIndentCommenting(blockSuitableCommenter);
      }
    }
    else {
      for (int line = myEndLine; line >= myStartLine; line--) {
        uncommentLine(line);
        //int offset1 = myStartOffsets[line - myStartLine];
        //int offset2 = myEndOffsets[line - myStartLine];
        //if (offset1 == offset2) continue;
        //Commenter commenter = myCommenters[line - myStartLine];
        //String prefix = commenter.getBlockCommentPrefix();
        //if (prefix == null || !myDocument.getText().substring(offset1, myDocument.getTextLength()).startsWith(prefix)) {
        //  prefix = commenter.getLineCommentPrefix();
        //}
        //
        //String suffix = commenter.getBlockCommentSuffix();
        //if (suffix == null && prefix != null) suffix = "";
        //
        //if (prefix != null && suffix != null) {
        //  final int suffixLen = suffix.length();
        //  final int prefixLen = prefix.length();
        //  if (offset2 >= 0) {
        //    if (!CharArrayUtil.regionMatches(chars, offset1 + prefixLen, prefix)) {
        //      myDocument.deleteString(offset2 - suffixLen, offset2);
        //    }
        //  }
        //  if (offset1 >= 0) {
        //    for (int i = offset2 - suffixLen - 1; i > offset1 + prefixLen; --i) {
        //      if (CharArrayUtil.regionMatches(chars, i, suffix)) {
        //        myDocument.deleteString(i, i + suffixLen);
        //      }
        //      else if (CharArrayUtil.regionMatches(chars, i, prefix)) {
        //        myDocument.deleteString(i, i + prefixLen);
        //      }
        //    }
        //    myDocument.deleteString(offset1, offset1 + prefixLen);
        //  }
        //}
      }
    }
  }

  private boolean isLineCommented(final int line, final CharSequence chars, final Commenter commenter) {
    String prefix = commenter.getLineCommentPrefix();
    int lineStart = myDocument.getLineStartOffset(line);
    lineStart = CharArrayUtil.shiftForward(chars, lineStart, " \t");
    boolean commented;
    if (prefix != null) {
      commented = CharArrayUtil.regionMatches(chars, lineStart, prefix);
      if (commented) {
        myStartOffsets[line - myStartLine] = lineStart;
        myEndOffsets[line - myStartLine] = -1;
      }
    }
    else {
      prefix = commenter.getBlockCommentPrefix();
      String suffix = commenter.getBlockCommentSuffix();
      final int textLength = myDocument.getTextLength();
      int lineEnd = myDocument.getLineEndOffset(line);
      if (lineEnd == textLength) {
        final int shifted = CharArrayUtil.shiftBackward(chars, textLength - 1, " \t");
        if (shifted < textLength - 1) lineEnd = shifted;
      }
      else {
        lineEnd = CharArrayUtil.shiftBackward(chars, lineEnd, " \t");
      }
      commented = (lineStart == lineEnd && myStartLine != myEndLine) || (CharArrayUtil.regionMatches(chars, lineStart, prefix) &&
                                                                   CharArrayUtil.regionMatches(chars, lineEnd - suffix.length(), suffix));
      if (commented) {
        myStartOffsets[line - myStartLine] = lineStart;
        myEndOffsets[line - myStartLine] = lineEnd;
      }
    }
    return commented;
  }

  @Nullable
  private Commenter findCommenter(final int line) {
    final FileType fileType = myFile.getFileType();
    if (fileType instanceof AbstractFileType) {
      return ((AbstractFileType)fileType).getCommenter();
    }

    int lineStartOffset = myDocument.getLineStartOffset(line);
    int lineEndOffset = myDocument.getLineEndOffset(line) - 1;
    final CharSequence charSequence = myDocument.getCharsSequence();
    lineStartOffset = CharArrayUtil.shiftForward(charSequence, lineStartOffset, " \t");
    lineEndOffset = CharArrayUtil.shiftBackward(charSequence, lineEndOffset < 0 ? 0 : lineEndOffset, " \t");
    final Language lineStartLanguage = PsiUtilBase.getLanguageAtOffset(myFile, lineStartOffset);
    final Language lineEndLanguage = PsiUtilBase.getLanguageAtOffset(myFile, lineEndOffset);
    return CommentByBlockCommentHandler.getCommenter(myFile, myEditor, lineStartLanguage, lineEndLanguage);
  }

  private Indent computeMinIndent(int line1, int line2, CharSequence chars, CodeStyleManager codeStyleManager, FileType fileType) {
    Indent minIndent = CommentUtil.getMinLineIndent(myProject, myDocument, line1, line2, fileType);
    if (line1 > 0) {
      int commentOffset = getCommentStart(line1 - 1);
      if (commentOffset >= 0) {
        int lineStart = myDocument.getLineStartOffset(line1 - 1);
        String space = chars.subSequence(lineStart, commentOffset).toString();
        Indent indent = codeStyleManager.getIndent(space, fileType);
        minIndent = minIndent != null ? indent.min(minIndent) : indent;
      }
    }
    if (minIndent == null) {
      minIndent = codeStyleManager.zeroIndent();
    }
    return minIndent;
  }

  private int getCommentStart(int line) {
    int offset = myDocument.getLineStartOffset(line);
    CharSequence chars = myDocument.getCharsSequence();
    offset = CharArrayUtil.shiftForward(chars, offset, " \t");
    final Commenter commenter = findCommenter(line);
    if (commenter == null) return -1;
    String prefix = commenter.getLineCommentPrefix();
    if (prefix == null) prefix = commenter.getBlockCommentPrefix();
    if (prefix == null) return -1;
    return CharArrayUtil.regionMatches(chars, offset, prefix) ? offset : -1;
  }

  public void doDefaultCommenting(Commenter commenter) {
    for (int line = myEndLine; line >= myStartLine; line--) {
      int offset = myDocument.getLineStartOffset(line);
      commentLine(line, offset, commenter);
    }
  }

  private void doIndentCommenting(Commenter commenter) {
    CharSequence chars = myDocument.getCharsSequence();
    final FileType fileType = myFile.getFileType();
    Indent minIndent = computeMinIndent(myStartLine, myEndLine, chars, myCodeStyleManager, fileType);

    for (int line = myEndLine; line >= myStartLine; line--) {
      int lineStart = myDocument.getLineStartOffset(line);
      int offset = lineStart;
      final StringBuilder buffer = StringBuilderSpinAllocator.alloc();
      try {
        while (true) {
          String space = buffer.toString();
          Indent indent = myCodeStyleManager.getIndent(space, fileType);
          if (indent.isGreaterThan(minIndent) || indent.equals(minIndent)) break;
          char c = chars.charAt(offset);
          if (c != ' ' && c != '\t') {
            String newSpace = myCodeStyleManager.fillIndent(minIndent, fileType);
            myDocument.replaceString(lineStart, offset, newSpace);
            offset = lineStart + newSpace.length();
            break;
          }
          buffer.append(c);
          offset++;
        }
      }
      finally {
        StringBuilderSpinAllocator.dispose(buffer);
      }
      commentLine(line, offset,commenter);
    }
  }

  private void uncommentRange(int startOffset, int endOffset, @NotNull Commenter commenter) {
    final String commentedSuffix = commenter.getCommentedBlockCommentSuffix();
    final String commentedPrefix = commenter.getCommentedBlockCommentPrefix();
    final String prefix = commenter.getBlockCommentPrefix();
    final String suffix = commenter.getBlockCommentSuffix();
    if (prefix == null || suffix == null) {
      return;
    }
    if (endOffset >= suffix.length() && CharArrayUtil.regionMatches(myDocument.getCharsSequence(), endOffset - suffix.length(), suffix)) {
      myDocument.deleteString(endOffset - suffix.length(), endOffset);
    }
    if (commentedPrefix != null && commentedSuffix != null) {
      CommentByBlockCommentHandler.commentNestedComments(myDocument, new TextRange(startOffset, endOffset), commenter);
    }
    myDocument.deleteString(startOffset, startOffset + prefix.length());
  }

  private void uncommentLine(int line) {
    Commenter commenter = myCommenters[line - myStartLine];
    if (commenter == null) commenter = findCommenter(line);
    if (commenter == null) return;

    final int startOffset = myStartOffsets[line - myStartLine];
    final int endOffset = myEndOffsets[line - myStartLine];
    if (startOffset == endOffset) {
      return;
    }
    String prefix = commenter.getLineCommentPrefix();
    if (prefix != null) {
      CharSequence chars = myDocument.getCharsSequence();
      assert CharArrayUtil.regionMatches(chars, startOffset, prefix);
      int position = 0;//text.indexOf(prefix);
      myDocument.deleteString(position + startOffset , position + startOffset + prefix.length());
      return;
    }
    String text = myDocument.getCharsSequence().subSequence(startOffset, endOffset).toString();

    prefix = commenter.getBlockCommentPrefix();
    final String suffix = commenter.getBlockCommentSuffix();
    if (prefix == null || suffix == null) {
      return;
    }

    IntArrayList prefixes = new IntArrayList();
    IntArrayList suffixes = new IntArrayList();
    for (int position = 0; position < text.length();) {
      int prefixPos = text.indexOf(prefix, position);
      if (prefixPos == -1) {
        break;
      }
      prefixes.add(prefixPos);
      position = prefixPos + prefix.length();
      int suffixPos = text.indexOf(suffix, position);
      if (suffixPos == -1) {
        suffixPos = text.length() - suffix.length();
      }
      suffixes.add(suffixPos);
      position = suffixPos + suffix.length();
    }

    assert prefixes.size() == suffixes.size();

    for (int i = prefixes.size() - 1; i >= 0; i--) {
      uncommentRange(startOffset + prefixes.get(i), Math.min(startOffset + suffixes.get(i) + suffix.length(), endOffset), commenter);
    }
  }

  private void commentLine(int line, int offset, @Nullable Commenter commenter) {
    if (commenter == null) commenter = findCommenter(line);
    if (commenter == null) return;
    String prefix = commenter.getLineCommentPrefix();
    if (prefix != null) {
      myDocument.insertString(offset, prefix);
    }
    else {
      prefix = commenter.getBlockCommentPrefix();
      String suffix = commenter.getBlockCommentSuffix();
      if (prefix == null || suffix == null) return;
      int endOffset = myDocument.getLineEndOffset(line);
      if (endOffset == offset && myStartLine != myEndLine) return;
      final int textLength = myDocument.getTextLength();
      final CharSequence chars = myDocument.getCharsSequence();
      offset = CharArrayUtil.shiftForward(chars, offset, " \t");
      if (endOffset == textLength) {
        final int shifted = CharArrayUtil.shiftBackward(chars, textLength - 1, " \t");
        if (shifted < textLength - 1) endOffset = shifted;
      }
      else {
        endOffset = CharArrayUtil.shiftBackward(chars, endOffset, " \t");
      }
      final String text = myDocument.getCharsSequence().subSequence(offset, endOffset).toString();
      final IntArrayList prefixes = new IntArrayList();
      final IntArrayList suffixes = new IntArrayList();
      final String commentedSuffix = commenter.getCommentedBlockCommentSuffix();
      final String commentedPrefix = commenter.getCommentedBlockCommentPrefix();
      for (int position = 0; position < text.length();) {
        int nearestPrefix = text.indexOf(prefix, position);
        if (nearestPrefix == -1) {
          nearestPrefix = text.length();
        }
        int nearestSuffix = text.indexOf(suffix, position);
        if (nearestSuffix == -1) {
          nearestSuffix = text.length();
        }
        if (Math.min(nearestPrefix, nearestSuffix) == text.length()) {
          break;
        }
        if (nearestPrefix < nearestSuffix) {
          prefixes.add(nearestPrefix);
          position = nearestPrefix + prefix.length();
        }
        else {
          suffixes.add(nearestSuffix);
          position = nearestSuffix + suffix.length();
        }
      }
      if (!(commentedSuffix == null && !suffixes.isEmpty() && offset + suffixes.get(suffixes.size() - 1) + suffix.length() >= endOffset)) {
        myDocument.insertString(endOffset, suffix);
      }
      int nearestPrefix = prefixes.size() - 1;
      int nearestSuffix = suffixes.size() - 1;
      while (nearestPrefix >= 0 || nearestSuffix >= 0) {
        if (nearestSuffix == -1 || nearestPrefix != -1 && prefixes.get(nearestPrefix) > suffixes.get(nearestSuffix)) {
          final int position = prefixes.get(nearestPrefix);
          nearestPrefix--;
          if (commentedPrefix != null) {
            myDocument.replaceString(offset + position, offset + position + prefix.length(), commentedPrefix);
          }
          else if (position != 0) {
            myDocument.insertString(offset + position, suffix);
          }
        }
        else {
          final int position = suffixes.get(nearestSuffix);
          nearestSuffix--;
          if (commentedSuffix != null) {
            myDocument.replaceString(offset + position, offset + position + suffix.length(), commentedSuffix);
          }
          else if (offset + position + suffix.length() < endOffset) {
            myDocument.insertString(offset + position + suffix.length(), prefix);
          }
        }
      }
      if (!(commentedPrefix == null && !prefixes.isEmpty() && prefixes.get(0) == 0)) {
        myDocument.insertString(offset, prefix);
      }
    }
  }
}
