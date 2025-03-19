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

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.*
import com.intellij.psi.util.elementType
import java.awt.event.MouseEvent
import com.intellij.util.Consumer
import com.jetbrains.python.psi.*
import com.jetbrains.python.psi.impl.*

class FST2StatusBar : StatusBarWidget, StatusBarWidget.TextPresentation {
    private var statusBar: StatusBar? = null
    private var currentType: String? = null
    private var currentName: String? = null

    // shamelessly adapted from
    // https://github.com/JetBrains/intellij-community/blob/3604aef6c833623213170816279b267bf2f5f698/platform/platform-impl/src/com/intellij/openapi/wm/impl/status/PositionPanel.kt
    init {
        val disposable = Disposer.newDisposable()
        val multicaster = EditorFactory.getInstance().eventMulticaster
        multicaster.addCaretListener(object: CaretListener {
            override fun caretPositionChanged(e: CaretEvent) {
                // when multiple carets exist in editor, we don't show information about caret positions
                val editor = e.editor
                if (editor.caretModel.caretCount == 1) {
                    updateType(e, editor)
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

    override fun ID(): String {
        return "FST2StatusBar"
    }

    override fun getText(): String {
        return this.currentType ?: ":("
    }

    fun updateType(event: CaretEvent, editor: Editor) {
        val caret = event.caret ?: return // if caret isn't null ...
        val project = editor.project ?: return // ... and project isn't null ...
        val psiFile: PsiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return
        // ... then from the current document get the PsiFile
        val offset = caret.offset
        val elementAtCaret: PsiElement = psiFile.findElementAt(offset) ?: return
        // ... and if the PsiElement at caret isn't null ...
        val exprEl = PsiTreeUtil.getParentOfType(elementAtCaret, PsiElement::class.java) ?: return
        // ... get the 'Expression' parent of that PsiElement

        var castedPt = (exprEl as? PyTargetExpression)
        // if it doesn't cast to PyTargetExpression ...
        if (castedPt == null) {
            castedPt = this.PyRef2PyTar(exprEl as? PyReferenceExpression)
        } // ... recast
        // and if still couldn't cast ...
        if (castedPt == null) {
            // (means this isn't a variable)
            this.currentType = null
            this.currentName = null
            this.statusBar?.updateWidget(ID())
            return
        } // ... unset values and return
        // ... and update values
        this.updateFromPyTar(castedPt)
        this.statusBar?.updateWidget(ID())
    }

    private fun Py2Name(pn: String, el: PsiElement): String {
        return when(pn) {
            "Py:STRING_LITERAL_EXPRESSION" -> "str"
            "Py:INTEGER_LITERAL_EXPRESSION" -> "int"
            "Py:LIST_LITERAL_EXPRESSION" -> "list"
            "Py:FLOAT_LITERAL_EXPRESSION" -> "float"
            "Py:DICT_LITERAL_EXPRESSION" -> "dict"
            "Py:SET_LITERAL_EXPRESSION" -> "set"
            "Py:BINARY_EXPRESSION" -> el.children.joinToString(separator = ", ") { this.Py2NameRefHelper(it) }
            "Py:PARENTHESIZED_EXPRESSION" -> {
                val castedMiddle = (el.firstChild.nextSibling as? PyTupleExpression)
                if (castedMiddle != null) { "tuple" } else { "tuple?" }
            }
            "Py:REFERENCE_EXPRESSION" -> {
                val recastPr = this.PyRef2PyTar(el as? PyReferenceExpression) ?: "?"
                this.Py2Name(recastPr.toString(), el)
            } // for nesteds
            "Py:CALL_EXPRESSION" -> "f(" + el.lastChild.children.joinToString(
                separator = ",", transform = { this.Py2NameRefHelper(it) }
            ) + ")"
            "ERROR_ELEMENT" -> ":("
            else -> {
                "?"
            }
        }
    }

    // Unwinds a PyReferenceExpression into it's PyTargetExpression
    private fun PyRef2PyTar(el: PyReferenceExpression?): PyTargetExpression? {
        if (el == null) return null
        val prRef = (el.reference.resolve() as? PyTargetExpression) ?: return null
        return prRef
    }

    // Unwinds a suspected PyReferenceExpression into rhs of a PyAssignmentStatement
    private fun PyRefish2PyRef(el: PsiElement): PsiElement? {
        val prRef = (el.reference?.resolve() as? PyTargetExpressionImpl) ?: return null
        val castedParent = (prRef.parent as? PyAssignmentStatement) ?: return null
        val rhs = castedParent.lastChild
        return rhs
    }

    // In a PyBinaryExpression and PyCallExpression we want the same thing:
    // Turn PyReferenceExpression into it's representation - but even though
    // they report "Py:REFERENCE_EXPRESSION" they're sometimes a PyTargetExpressionImpl
    // so this function unwinds them
    private fun Py2NameRefHelper(el: PsiElement?): String {
        if (el == null) return ""
        if (el.node.elementType.toString() == "Py:REFERENCE_EXPRESSION") {
            val rhs = this.PyRefish2PyRef(el)
            if (rhs != null) {
                return this.Py2Name(rhs.elementType.toString(), rhs)
            } else {
                return "?"
            }
        } else {
            return this.Py2Name(el.node.elementType.toString(), el)
        }
    }

    private fun updateFromPyTar(el: PyTargetExpression) {
        val castedParent = (el.parent as? PyAssignmentStatement) ?: return
        // ... get our PyAssignmentStatement parent ...
        val rhs = castedParent.lastChild ?: return
        // ... and their assigned value ...
        this.currentType = this.Py2Name(rhs.node.elementType.toString(), rhs)
        // ... and set our type to its String name converted to a familiar Python type name ...
        this.currentName = castedParent.firstChild?.text
        // ... and set our name to its String name ...
    }

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
        println("currentType = ${this.currentType}!")
        this.statusBar?.updateWidget(ID())
    }

    override fun getPresentation(): WidgetPresentation {
        return this
    }
    override fun getAlignment(): Float {
        return 0.0f // left
    }
    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
    }
    override fun getTooltipText(): String {
        return "Current variable's name is: ${this.currentName ?: "unknown :("}"
    }
}