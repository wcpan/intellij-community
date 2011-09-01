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

/*
 * Created by IntelliJ IDEA.
 * User: mike
 * Date: Aug 20, 2002
 * Time: 5:04:04 PM
 * To change template for new class use
 * Code Style | Class Templates options (Tools | IDE Options).
 */
package com.intellij.codeInsight.template.actions;

import com.intellij.codeInsight.template.TemplateContextType;
import com.intellij.codeInsight.template.impl.LiveTemplatesConfigurable;
import com.intellij.codeInsight.template.impl.TemplateImpl;
import com.intellij.codeInsight.template.impl.TemplateSettings;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiElementFilter;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.containers.HashMap;

import java.util.Map;

public class SaveAsTemplateAction extends AnAction {

  public void actionPerformed(AnActionEvent e) {
    DataContext dataContext = e.getDataContext();
    final Editor editor = PlatformDataKeys.EDITOR.getData(dataContext);
    PsiFile file = LangDataKeys.PSI_FILE.getData(dataContext);

    final Project project = file.getProject();
    PsiDocumentManager.getInstance(project).commitAllDocuments();

    final TextRange selection = new TextRange(editor.getSelectionModel().getSelectionStart(),
                                              editor.getSelectionModel().getSelectionEnd());
    PsiElement current = file.findElementAt(selection.getStartOffset());
    int startOffset = selection.getStartOffset();
    while (current instanceof PsiWhiteSpace) {
      current = current.getNextSibling();
      if (current == null) break;
      startOffset = current.getTextRange().getStartOffset();
    }

    if (startOffset >= selection.getEndOffset()) startOffset = selection.getStartOffset();

    final PsiElement[] psiElements = PsiTreeUtil.collectElements(file, new PsiElementFilter() {
      public boolean isAccepted(PsiElement element) {
        return selection.contains(element.getTextRange()) && element.getReferences().length > 0;
      }
    });

    final Document document = EditorFactory.getInstance().createDocument(editor.getDocument().getText().
                                                                         substring(startOffset,
                                                                                   selection.getEndOffset()));
    final int offsetDelta = startOffset;
    CommandProcessor.getInstance().executeCommand(project, new Runnable() {
      public void run() {
        ApplicationManager.getApplication().runWriteAction(new Runnable() {
          public void run() {
            Map<RangeMarker, String> rangeToText = new HashMap<RangeMarker, String>();

            for (PsiElement element : psiElements) {
              for (PsiReference reference : element.getReferences()) {
                if (!(reference instanceof PsiQualifiedReference) || ((PsiQualifiedReference) reference).getQualifier() == null) {
                  String canonicalText = reference.getCanonicalText();
                  TextRange referenceRange = reference.getRangeInElement();
                  TextRange range = element.getTextRange().cutOut(referenceRange).shiftRight(-offsetDelta);
                  final String oldText = document.getText(range);
                  // workaround for Java references: canonicalText contains generics, and we need to cut them off because otherwise
                  // they will be duplicated
                  int pos = canonicalText.indexOf('<');
                  if (pos > 0 && !oldText.contains("<")) {
                    canonicalText = canonicalText.substring(0, pos);
                  }
                  if (!canonicalText.equals(oldText)) {
                    rangeToText.put(document.createRangeMarker(range), canonicalText);
                  }
                }
              }
            }

            for (Map.Entry<RangeMarker, String> entry : rangeToText.entrySet()) {
              document.replaceString(entry.getKey().getStartOffset(), entry.getKey().getEndOffset(), entry.getValue());
            }
          }
        });
      }
    }, null, null);

    final TemplateImpl template = new TemplateImpl("<abbreviation>", document.getText(), TemplateSettings.USER_GROUP_NAME);

    for(TemplateContextType contextType: Extensions.getExtensions(TemplateContextType.EP_NAME)) {
      template.getTemplateContext().setEnabled(contextType, contextType.isInContext(file, startOffset));
    }

    final LiveTemplatesConfigurable configurable = new LiveTemplatesConfigurable();
    ShowSettingsUtil.getInstance().editConfigurable(project, configurable, new Runnable() {
      @Override
      public void run() {
        configurable.getTemplateListPanel().addTemplate(template);
      }
    });
  }

  public void update(AnActionEvent e) {
    DataContext dataContext = e.getDataContext();
    Editor editor = PlatformDataKeys.EDITOR.getData(dataContext);
    PsiFile file = LangDataKeys.PSI_FILE.getData(dataContext);

    if (file == null || editor == null) {
      e.getPresentation().setEnabled(false);
    }
    else {
      e.getPresentation().setEnabled(editor.getSelectionModel().hasSelection());
    }
  }
}
