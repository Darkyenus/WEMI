package com.darkyen.wemi.intellij.execution

import com.darkyen.wemi.intellij.options.BeforeRunOptions
import com.darkyen.wemi.intellij.settings.WemiProjectService
import com.darkyen.wemi.intellij.ui.PropertyEditorPanel
import com.intellij.execution.BeforeRunTask
import com.intellij.execution.BeforeRunTaskProvider
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.impl.RunConfigurationBeforeRunProvider
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.rd.attach
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Key
import icons.WemiIcons
import org.jdom.Element
import org.jetbrains.concurrency.AsyncPromise
import org.jetbrains.concurrency.Promise
import javax.swing.JComponent

val WemiBeforeRunTaskKey = Key.create<WemiBeforeRunTask>("Wemi.BeforeRunTask")

/** Task that allows Wemi task to run before other tasks. */
class WemiBeforeRunTask : BeforeRunTask<WemiBeforeRunTask>(WemiBeforeRunTaskKey), PersistentStateComponent<Element> {

	var options = BeforeRunOptions()

	override fun getState(): Element? = options.state

	override fun loadState(state: Element) {
		options.loadState(state)
	}

	override fun clone(): WemiBeforeRunTask {
		val clone = WemiBeforeRunTask()
		options.copyTo(clone.options)
		return clone
	}

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is WemiBeforeRunTask) return false
		if (!super.equals(other)) return false

		if (options != other.options) return false

		return true
	}

	override fun hashCode(): Int {
		var result = super.hashCode()
		result = 31 * result + options.hashCode()
		return result
	}

}


/**
 * Provides ability to run Wemi task before any run configuration.
 *
 * The task uses the same settings controller as the Wemi Run Configuration.
 */
class WemiBeforeRunTaskProvider(private val project: Project) : BeforeRunTaskProvider<WemiBeforeRunTask>() { //ExternalSystemBeforeRunTaskProvider(WemiProjectSystemId, project, ID) {

	override fun getId(): Key<WemiBeforeRunTask> = WemiBeforeRunTaskKey

	/** Icon used in the "Before launch" window in "Run/Debug Configurations" */
	override fun getIcon() = WemiIcons.ACTION

	override fun getName(): String = "Run Wemi task"

	override fun getDescription(task: WemiBeforeRunTask?): String {
		if (task == null) {
			return name
		}
		return task.options.shortTaskSummary()
	}

	override fun isConfigurable(): Boolean = true

	override fun createTask(runConfiguration: RunConfiguration): WemiBeforeRunTask? {
		// NOTE: Creating Wemi before run tasks is intentionally allowed for chains with other tasks
		return WemiBeforeRunTask().apply {
			project.getService(WemiProjectService::class.java)?.options?.copyTo(this.options)
		}
	}

	override fun configureTask(context: DataContext, configuration: RunConfiguration, task: WemiBeforeRunTask): Promise<Boolean> {
		val dialog = EditBeforeRunDialog(project, task.options)
		dialog.isModal = true
		val promise = AsyncPromise<Boolean>()
		dialog.disposable.attach {
			promise.setResult(dialog.isOK)
		}
		dialog.show()
		return promise
	}

	override fun executeTask(context: DataContext, configuration: RunConfiguration, env: ExecutionEnvironment, beforeRunTask: WemiBeforeRunTask): Boolean {
		val runner = WemiProgramRunner.instance()
		val executor = DefaultRunExecutor.getRunExecutorInstance()

		val taskConfiguration = WemiTaskConfiguration(project, WemiTaskConfigurationType.INSTANCE.taskConfigurationFactory, beforeRunTask.options.shortTaskSummary())
		beforeRunTask.options.copyTo(taskConfiguration.options)
		val runnerAndConfigurationSettings = RunManager.getInstance(project).createConfiguration(taskConfiguration, taskConfiguration.factory)
		val environment = ExecutionEnvironment(executor, runner, runnerAndConfigurationSettings, project)
		environment.executionId = env.executionId

		return RunConfigurationBeforeRunProvider.doRunTask(executor.id, environment, runner)
	}

	private class EditBeforeRunDialog(project: Project, private val options: BeforeRunOptions) : DialogWrapper(project, true) {

		private val contentPane: PropertyEditorPanel by lazy {
			val panel = PropertyEditorPanel()
			options.createUi(panel)
			panel.loadFromProperties()
			panel
		}

		init {
			title = "Configure Wemi invocation"
			init()
		}

		override fun createCenterPanel(): JComponent? = contentPane

		override fun getPreferredFocusedComponent(): JComponent? = contentPanel

		override fun doOKAction() {
			contentPane.saveToProperties()
			super.doOKAction()
		}
	}
}