package com.fst2.nev.fst2

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.*
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidget.WidgetPresentation
import com.intellij.openapi.wm.StatusBar
import java.awt.event.MouseEvent
import com.intellij.util.Consumer

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.*
import com.jetbrains.python.psi.*
import com.jetbrains.python.psi.impl.*

class FST2StatusBar : StatusBarWidget, StatusBarWidget.TextPresentation {
    private var statusBar: StatusBar? = null
    private var currentType: String? = null
    private var currentName: String? = null
    private var debug: String? = null

    // shamelessly adapted from JetBrains' PositionPanel Plugin
    // https://github.com/JetBrains/intellij-community/blob/3604aef6c833623213170816279b267bf2f5f698/platform/platform-impl/src/com/intellij/openapi/wm/impl/status/PositionPanel.kt
    init {
        val disposable = Disposer.newDisposable()
        val multicaster = EditorFactory.getInstance().eventMulticaster
        multicaster.addCaretListener(object: CaretListener {
            override fun caretPositionChanged(e: CaretEvent) {
                val editor = e.editor
                // with multiple carets we don't check types
                if (editor.caretModel.caretCount == 1) {
                    updateType(e, editor)
                } else {
                    setCurrents(null, null)
                }
            }

            override fun caretAdded(e: CaretEvent) {
                updateType(e, e.editor)
            }

            override fun caretRemoved(e: CaretEvent) {
                currentType = null
            }
        }, disposable)
        multicaster.addSelectionListener(object : SelectionListener {
            override fun selectionChanged(e: SelectionEvent) {
                currentType = null
            }
        }, disposable)
    }

    fun updateType(event: CaretEvent, editor: Editor) {
        val caret = event.caret ?: return
        val project = editor.project ?: return
        val psiFile: PsiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return
        val offset = caret.offset
        val elementAtCaret: PsiElement = psiFile.findElementAt(offset) ?: return
        // get the 'Expression' parent of that PsiElement
        val exprEl = PsiTreeUtil.getParentOfType(elementAtCaret, PsiElement::class.java) ?: return
        this.debug = "${exprEl.node.elementType}, ${exprEl.text}"

        var castedPt = (exprEl as? PyTargetExpression)
        // if it doesn't cast to PyTargetExpression (at the definition),
        // try as a PyReferenceExpression (at an use)
        if (castedPt == null) {
            castedPt = this.pyReference2PyTarget(exprEl as? PyReferenceExpression)
        }
        // and if still couldn't cast, it wasn't a variable, so we update to null
        if (castedPt == null) {
            this.setCurrents(null, null)
            return
        }
        val rhs: PsiElement? = this.pyTarget2RHS(castedPt)
        this.setCurrents(this.nodeToType(rhs), this.nodeToName(rhs))
    }

    private fun nodeToName(el: PsiElement?): String {
        if (el == null) return "?"
        val castedParent = (el.parent as? PyAssignmentStatement) ?: return "?"
        return castedParent.firstChild.text
    }
    private fun nodeToType(el: PsiElement?): String {
        if (el == null) return "?"
        return this.py2Name(el.node.elementType.toString(), el)
    }

    private fun setCurrents(type: String?, name: String?) {
        this.currentType = type
        this.currentName = name
        this.statusBar?.updateWidget(ID())
    }

    private fun py2Name(pn: String, el: PsiElement): String {
        return when(pn) {
            "Py:STRING_LITERAL_EXPRESSION" -> "str"
            "Py:INTEGER_LITERAL_EXPRESSION" -> "int"
            "Py:LIST_LITERAL_EXPRESSION" -> "list"
            "Py:FLOAT_LITERAL_EXPRESSION" -> "float"
            "Py:DICT_LITERAL_EXPRESSION" -> "dict"
            "Py:SET_LITERAL_EXPRESSION" -> "set"
            "Py:BINARY_EXPRESSION" -> el.children.joinToString(
                separator = ", ", transform = { this.py2NameRefHelper(it) }
            ) 
            // this is sometimes not perfect; an expression "7+7" will be resolved to "int, int",
            // and when used in a function call later, "f(int, int)",
            // which will be understood as a function that takes two ints as parameters
            "Py:PARENTHESIZED_EXPRESSION" -> {
                val castedMiddle = (el.firstChild.nextSibling as? PyTupleExpression)
                if (castedMiddle != null) { "tuple" } else { "tuple?" }
            }
            "Py:REFERENCE_EXPRESSION" -> {
                val recastPr = this.pyReference2PyTarget(el as? PyReferenceExpression) ?: "?"
                this.py2Name(recastPr.toString(), el)
            }
            "Py:CALL_EXPRESSION" -> "f(" + el.lastChild.children.joinToString(
                separator = ", ", transform = { this.py2NameRefHelper(it) }
            ) + ")"
            "ERROR_ELEMENT" -> ":("
            else -> {
                "?"
            }
        }
    }

    private fun pyReference2PyTarget(el: PyReferenceExpression?): PyTargetExpression? {
        if (el == null) return null
        val prRef = (el.reference.resolve() as? PyTargetExpression) ?: return null
        return prRef
    }

    private fun pyReference2RHS(el: PyReferenceExpression?): PsiElement? {
        val prRef = (el?.reference?.resolve() as? PyTargetExpressionImpl) ?: return null
        val castedParent = (prRef.parent as? PyAssignmentStatement) ?: return null
        return castedParent.lastChild // rhs
    }

    private fun pyTarget2RHS(el: PyTargetExpression?): PsiElement? {
        val castedParent = (el?.parent as? PyAssignmentStatement) ?: return null
        return castedParent.lastChild // rhs
    }

    // In a PyBinaryExpression and PyCallExpression we want the same thing:
    // Turn PyReferenceExpression into it's representation - but even though
    // they report "Py:REFERENCE_EXPRESSION" they're sometimes a PyTargetExpressionImpl
    // so this function unwinds them
    private fun py2NameRefHelper(el: PsiElement?): String {
        if (el == null) {
            return ""
        } else if (el.node.elementType.toString() == "Py:REFERENCE_EXPRESSION") {
            return this.nodeToType(this.pyReference2RHS((el as? PyReferenceExpression)))
        } else {
            return this.py2Name(el.node.elementType.toString(), el)
        }
    }

    override fun ID(): String { return "FST2StatusBar" }
    override fun getText(): String { return this.currentType ?: ":(" }
    override fun getTooltipText(): String { return this.currentName ?: ":(" }
    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer { println("Debug: ${this.debug}") }
    override fun getPresentation(): WidgetPresentation { return this }
    override fun getAlignment(): Float { return 0.0f }
    override fun install(statusBar: StatusBar) { this.statusBar = statusBar }
}