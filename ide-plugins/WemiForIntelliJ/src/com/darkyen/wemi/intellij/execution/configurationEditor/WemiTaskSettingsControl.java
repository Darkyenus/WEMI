/*
 * Copyright 2000-2013 JetBrains s.r.o.
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

package com.darkyen.wemi.intellij.execution.configurationEditor;

import com.darkyen.wemi.intellij.WemiKt;
import com.darkyen.wemi.intellij.execution.WemiRunConfiguration;
import com.darkyen.wemi.intellij.manager.WemiManager;
import com.intellij.execution.configuration.EnvironmentVariablesComponent;
import com.intellij.execution.configuration.EnvironmentVariablesData;
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings;
import com.intellij.openapi.externalSystem.service.execution.TaskCompletionProvider;
import com.intellij.openapi.externalSystem.service.ui.ExternalProjectPathField;
import com.intellij.openapi.externalSystem.util.*;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.RawCommandLineEditor;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.GridBag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;

import static com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil.normalizePath;

/** Controls editing of standard ExternalSystemTaskExecutionSettings but in a way that makes sense for Wemi.
 *
 * Modifications:
 * - Arguments are not used, instead checkbox to set WemiRunConfiguration.WEMI_CONFIGURATION_ARGUMENT_SUPPRESS_DEBUG
 * */
public class WemiTaskSettingsControl implements ExternalSystemSettingsControl<ExternalSystemTaskExecutionSettings> {

	@NotNull private final Project myProject;

	@SuppressWarnings("FieldCanBeLocal")// Used via reflection at showUi() and disposeResources()
	private JBLabel myProjectPathLabel;
	private ExternalProjectPathField myProjectPathField;

	@SuppressWarnings("FieldCanBeLocal")// Used via reflection at showUi() and disposeResources()
	private JBLabel myTasksLabel;
	private EditorTextField myTasksTextField;

	@SuppressWarnings("FieldCanBeLocal")// Used via reflection at showUi() and disposeResources()
	private JBLabel myVmOptionsLabel;
	private RawCommandLineEditor myVmOptionsEditor;

	private JBCheckBox suppressDebug;

	private EnvironmentVariablesComponent myEnvVariablesComponent;

	@Nullable private ExternalSystemTaskExecutionSettings myOriginalSettings;

	public WemiTaskSettingsControl (@NotNull Project project) {
		myProject = project;
	}

	public void setOriginalSettings (@Nullable ExternalSystemTaskExecutionSettings originalSettings) {
		myOriginalSettings = originalSettings;
	}

	@Override
	public void fillUi (@NotNull final PaintAwarePanel canvas, int indentLevel) {
		myProjectPathLabel = new JBLabel(ExternalSystemBundle.message("run.configuration.settings.label.project", WemiKt
			.getWemiProjectSystemId().getReadableName()));
		final WemiManager manager = (WemiManager)ExternalSystemApiUtil.getManager(WemiKt.getWemiProjectSystemId());
		assert manager != null;
		final FileChooserDescriptor projectPathChooserDescriptor = manager.getExternalProjectConfigDescriptor();
		assert projectPathChooserDescriptor != null;

		String title = ExternalSystemBundle.message("settings.label.select.project", WemiKt.getWemiProjectSystemId()
			.getReadableName());
		myProjectPathField = new ExternalProjectPathField(myProject, WemiKt.getWemiProjectSystemId(), projectPathChooserDescriptor,
			title) {
			@Override
			public Dimension getPreferredSize () {
				return myVmOptionsEditor == null ? super.getPreferredSize() : myVmOptionsEditor.getTextField().getPreferredSize();
			}
		};
		canvas.add(myProjectPathLabel, ExternalSystemUiUtil.getLabelConstraints(0));
		canvas.add(myProjectPathField, ExternalSystemUiUtil.getFillLineConstraints(0));

		myTasksLabel = new JBLabel(ExternalSystemBundle.message("run.configuration.settings.label.tasks"));
		myTasksTextField = new EditorTextField("", myProject, PlainTextFileType.INSTANCE);
		canvas.add(myTasksLabel, ExternalSystemUiUtil.getLabelConstraints(0));
		GridBag c = ExternalSystemUiUtil.getFillLineConstraints(0);
		c.insets.right = myProjectPathField.getButton().getPreferredSize().width + 8 /* street magic, sorry */;
		canvas.add(myTasksTextField, c);

		new TaskCompletionProvider(myProject, WemiKt.getWemiProjectSystemId(), myProjectPathField).apply(myTasksTextField);

		myVmOptionsLabel = new JBLabel(ExternalSystemBundle.message("run.configuration.settings.label.vmoptions"));
		myVmOptionsEditor = new RawCommandLineEditor();
		myVmOptionsEditor.setDialogCaption(ExternalSystemBundle.message("run.configuration.settings.label.vmoptions"));
		canvas.add(myVmOptionsLabel, ExternalSystemUiUtil.getLabelConstraints(0));
		canvas.add(myVmOptionsEditor, ExternalSystemUiUtil.getFillLineConstraints(0));

		suppressDebug = new JBCheckBox("Suppress Wemi launcher debugging to debug launched project");
		canvas.add(suppressDebug, ExternalSystemUiUtil.getFillLineConstraints(0));

		myEnvVariablesComponent = new EnvironmentVariablesComponent();
		JBLabel myEnvVariablesComponentLabel = myEnvVariablesComponent.getLabel();
		myEnvVariablesComponentLabel.remove(myEnvVariablesComponentLabel);
		canvas.add(myEnvVariablesComponentLabel, ExternalSystemUiUtil.getLabelConstraints(0));
		canvas.add(myEnvVariablesComponent, ExternalSystemUiUtil.getFillLineConstraints(0));
	}

	@Override
	public void reset () {
		myProjectPathField.setText("");
		myTasksTextField.setText("");
		myVmOptionsEditor.setText("");
		suppressDebug.setSelected(false);
		myEnvVariablesComponent.setEnvData(EnvironmentVariablesData.DEFAULT);
		showUi(true);

		if (myOriginalSettings == null) {
			return;
		}

		String path = myOriginalSettings.getExternalProjectPath();
		if (StringUtil.isEmpty(path)) {
			path = "";
		}
		myProjectPathField.setText(path);
		myTasksTextField.setText(StringUtil.join(myOriginalSettings.getTaskNames(), " "));
		myVmOptionsEditor.setText(myOriginalSettings.getVmOptions());
		suppressDebug.setSelected(isSuppressDebug(myOriginalSettings));
		myEnvVariablesComponent.setEnvData(EnvironmentVariablesData.create(myOriginalSettings.getEnv(),
			myOriginalSettings.isPassParentEnvs()));
	}

	@Override
	public boolean isModified () {
		// noinspection SimplifiableIfStatement
		if (myOriginalSettings == null) {
			return false;
		}

		return !Comparing.equal(normalizePath(myProjectPathField.getText()),
			normalizePath(myOriginalSettings.getExternalProjectPath()))
			|| !Comparing.equal(normalizePath(myTasksTextField.getText()),
				normalizePath(StringUtil.join(myOriginalSettings.getTaskNames(), " ")))
			|| !Comparing.equal(normalizePath(myVmOptionsEditor.getText()), normalizePath(myOriginalSettings.getVmOptions()))
			|| isSuppressDebug(myOriginalSettings) != suppressDebug.isSelected()
			|| myEnvVariablesComponent.isPassParentEnvs() != myOriginalSettings.isPassParentEnvs()
			|| !myEnvVariablesComponent.getEnvs().equals(myOriginalSettings.getEnv());

	}

	@Override
	public void apply (@NotNull ExternalSystemTaskExecutionSettings settings) {
		String projectPath = myProjectPathField.getText();
		settings.setExternalProjectPath(projectPath);
		settings.setTaskNames(StringUtil.split(myTasksTextField.getText(), " "));
		settings.setVmOptions(myVmOptionsEditor.getText());
		setSuppressDebug(settings, suppressDebug.isSelected());
		settings.setPassParentEnvs(myEnvVariablesComponent.isPassParentEnvs());
		settings.setEnv(ContainerUtil.newHashMap(myEnvVariablesComponent.getEnvs()));
	}

	private static boolean isSuppressDebug(ExternalSystemTaskExecutionSettings settings) {
		return WemiRunConfiguration.Companion.getWEMI_CONFIGURATION_ARGUMENT_SUPPRESS_DEBUG().equals(settings.getScriptParameters());
	}

	private static void setSuppressDebug(ExternalSystemTaskExecutionSettings settings, boolean suppressDebug) {
		settings.setScriptParameters(suppressDebug ? WemiRunConfiguration.Companion.getWEMI_CONFIGURATION_ARGUMENT_SUPPRESS_DEBUG() : "");
	}

	@Override
	public boolean validate (@NotNull ExternalSystemTaskExecutionSettings settings) throws ConfigurationException {
		String projectPath = myProjectPathField.getText();
		if (myOriginalSettings == null) {
			throw new ConfigurationException(
				String.format(
					"Can't store external task settings into run configuration. Reason: target run configuration is undefined. Tasks: '%s', "
						+ "external project: '%s', vm options: '%s'", myTasksTextField.getText(), projectPath,
					myVmOptionsEditor.getText()));
		}
		return true;
	}

	@Override
	public void disposeUIResources () {
		ExternalSystemUiUtil.disposeUi(this);
	}

	@Override
	public void showUi (boolean show) {
		ExternalSystemUiUtil.showUi(this, show);
	}
}