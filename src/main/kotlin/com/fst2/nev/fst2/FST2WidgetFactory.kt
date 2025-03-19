package com.fst2.nev.fst2

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

class FST2WidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String { return "FST2StatusBar" }

    override fun getDisplayName(): String { return "FST2 Status Bar Widget" }

    override fun isAvailable(project: Project): Boolean { return true }

    override fun createWidget(project: Project): StatusBarWidget { return FST2StatusBar() }

    override fun disposeWidget(widget: StatusBarWidget) {}

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean { return true }
}
