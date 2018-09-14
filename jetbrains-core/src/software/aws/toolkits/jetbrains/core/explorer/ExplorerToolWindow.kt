// Copyright 2018 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.core.explorer

import com.intellij.execution.Location
import com.intellij.ide.util.treeView.AbstractTreeBuilder
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.ide.util.treeView.NodeRenderer
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.ex.ActionManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.TreeUIHelper
import com.intellij.ui.components.panels.Wrapper
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBSwingUtilities
import com.intellij.util.ui.UIUtil
import software.aws.toolkits.core.credentials.ToolkitCredentialsProvider
import software.aws.toolkits.core.region.AwsRegion
import software.aws.toolkits.jetbrains.core.credentials.ProjectAccountSettingsManager
import software.aws.toolkits.jetbrains.core.credentials.ProjectAccountSettingsManager.AccountSettingsChangedNotifier
import software.aws.toolkits.jetbrains.core.explorer.ExplorerDataKeys.SELECTED_RESOURCE_NODES
import software.aws.toolkits.jetbrains.core.explorer.ExplorerDataKeys.SELECTED_SERVICE_NODE
import software.aws.toolkits.jetbrains.services.lambda.LambdaFunctionNode
import software.aws.toolkits.jetbrains.services.lambda.execution.remote.RemoteLambdaLocation
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class ExplorerToolWindow(val project: Project) : SimpleToolWindowPanel(true, true), AccountSettingsChangedNotifier {
    private val actionManager = ActionManagerEx.getInstanceEx()

    private val treePanelWrapper: Wrapper = Wrapper()
    private val errorPanel: JPanel
    private var awsTree: Tree? = null

    init {
        val link = HyperlinkLabel("TODO: Add links to the getting started pages")

        errorPanel = JPanel()
        errorPanel.add(link)

        setContent(treePanelWrapper)

        project.messageBus.connect().subscribe(ProjectAccountSettingsManager.ACCOUNT_SETTINGS_CHANGED, this)

        updateModel()
    }

    override fun activeCredentialsChanged(credentialsProvider: ToolkitCredentialsProvider) {
        updateModel()
    }

    override fun activeRegionChanged(value: AwsRegion) {
        updateModel()
    }

    private fun updateModel() {
        if (!ProjectAccountSettingsManager.getInstance(project).hasActiveCredentials()) {
            treePanelWrapper.setContent(errorPanel)
            return
        }

        val model = DefaultTreeModel(DefaultMutableTreeNode())
        val newTree = createTree(model)
        val builder = AwsExplorerTreeBuilder(newTree, model, project)
        Disposer.register(project, builder)
        treePanelWrapper.setContent(ScrollPaneFactory.createScrollPane(newTree))

        awsTree?.let {
            AbstractTreeBuilder.getBuilderFor(it)?.let {
                Disposer.dispose(it)
            }
        }

        awsTree = newTree
    }

    private fun createTree(model: DefaultTreeModel): Tree {
        val awsTree = Tree()
        TreeUIHelper.getInstance().installTreeSpeedSearch(awsTree)
        UIUtil.setLineStyleAngled(awsTree)
        awsTree.isRootVisible = false
        awsTree.autoscrolls = true
        awsTree.cellRenderer = AwsTreeCellRenderer()
        awsTree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (JBSwingUtilities.isLeftMouseButton(event) && event.clickCount == 2) {
                    val selectedElement = awsTree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode ?: return
                    val awsExplorerNode = selectedElement.userObject as? AwsExplorerNode<*> ?: return
                    awsExplorerNode.onDoubleClick(model, selectedElement)
                }
            }
        })

        awsTree.addMouseListener(object : PopupHandler() {
            override fun invokePopup(comp: Component?, x: Int, y: Int) {
                // Build a right click menu based on the selected first node
                // All nodes must be the same type (e.g. all S3 buckets, or a service node)
                val explorerNode = getSelectedNodesSameType<AwsExplorerNode<*>>()?.get(0) ?: return
                val actionGroupName = when (explorerNode) {
                    is AwsExplorerServiceRootNode ->
                        "aws.toolkit.explorer.${explorerNode.serviceName()}"
                    is AwsExplorerResourceNode<*> ->
                        "aws.toolkit.explorer.${explorerNode.serviceName()}.${explorerNode.resourceType()}"
                    else ->
                        return
                }
                val actionGroup = actionManager.getAction(actionGroupName) as? ActionGroup ?: return
                val popupMenu = actionManager.createActionPopupMenu(ActionPlaces.UNKNOWN, actionGroup)
                popupMenu.component.show(component, x, y)
            }
        })

        return awsTree
    }

    override fun getData(dataId: String?): Any? {
        if (SELECTED_RESOURCE_NODES.`is`(dataId)) {
            return getSelectedNodesSameType<AwsExplorerResourceNode<*>>()
        }
        if (SELECTED_SERVICE_NODE.`is`(dataId)) {
            return getSelectedServiceNode()
        }
        if (Location.DATA_KEY.`is`(dataId)) {
            val lambdas = getSelectedNodesSameType<LambdaFunctionNode>()
            if (lambdas?.size != 1) {
                return null
            }

            return RemoteLambdaLocation(project, lambdas.first().function)
        }

        return super.getData(dataId)
    }

    private fun getSelectedNode(): AwsExplorerNode<*>? {
        val nodes = getSelectedNodes<AwsExplorerNode<*>>()
        return if (nodes.size == 1) nodes[0] else null
    }

    private fun getSelectedServiceNode(): AwsExplorerServiceRootNode? {
        return getSelectedNode() as? AwsExplorerServiceRootNode
    }

    /**
     * Returns the list of selected nodes if they are all of the same type
     */
    private inline fun <reified T : AwsExplorerNode<*>> getSelectedNodesSameType(): List<T>? {
        val selectedNodes = getSelectedNodes<T>()
        if (selectedNodes.isEmpty()) {
            return null
        }

        val firstClass = selectedNodes[0]::class.java
        return if (selectedNodes.all { firstClass.isInstance(it) }) {
            selectedNodes
        } else {
            null
        }
    }

    private inline fun <reified T : AwsExplorerNode<*>> getSelectedNodes(): List<T> {
        return awsTree?.selectionPaths?.let {
            it.map { it.lastPathComponent }
                .filterIsInstance<DefaultMutableTreeNode>()
                .map { it.userObject }
                .filterIsInstance<T>()
                .toList()
        } ?: emptyList<T>()
    }

    private class AwsTreeCellRenderer : NodeRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean
        ) {
            super.customizeCellRenderer(tree, value, selected, expanded, leaf, row, hasFocus)
            if (value is DefaultMutableTreeNode && value.userObject is NodeDescriptor<*>) {
                icon = (value.userObject as NodeDescriptor<*>).icon
            }
        }
    }
}

object ExplorerDataKeys {
    /**
     * Returns all the selected resource nodes. getData() will return null if not all selected items are same type
     */
    val SELECTED_RESOURCE_NODES = DataKey.create<List<AwsExplorerResourceNode<*>>>("aws.explorer.resourceNodes")
    /**
     * Returns the selected Service node. getData() will return null if more than one item is selected
     */
    val SELECTED_SERVICE_NODE = DataKey.create<AwsExplorerNode<*>>("aws.explorer.serviceNode")
}