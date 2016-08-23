/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2016 Serge Rieder (serge@jkiss.org)
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (version 2)
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package org.jkiss.dbeaver.ui.controls.resultset.panel;

import org.eclipse.jface.action.*;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.*;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBIcon;
import org.jkiss.dbeaver.model.DBPImage;
import org.jkiss.dbeaver.model.data.DBDAttributeBinding;
import org.jkiss.dbeaver.model.data.aggregate.IAggregateFunction;
import org.jkiss.dbeaver.registry.functions.AggregateFunctionDescriptor;
import org.jkiss.dbeaver.registry.functions.FunctionsRegistry;
import org.jkiss.dbeaver.ui.DBeaverIcons;
import org.jkiss.dbeaver.ui.UIIcon;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.controls.resultset.*;

import java.util.*;
import java.util.List;

/**
 * RSV value view panel
 */
public class AggregateColumnsPanel implements IResultSetPanel {

    private static final Log log = Log.getLog(AggregateColumnsPanel.class);

    public static final String PANEL_ID = "column-aggregate";

    public static final String SETTINGS_SECTION_AGGREGATE = "panel-" + PANEL_ID;
    public static final String PARAM_GROUP_BY_COLUMNS = "groupByColumns";

    private IResultSetPresentation presentation;
    private Tree aggregateTable;

    private boolean groupByColumns;
    private boolean runServerQueries;

    private IContributionManager panelToolbar;
    private IDialogSettings panelSettings;

    private final List<AggregateFunctionDescriptor> enabledFunctions = new ArrayList<>();

    public AggregateColumnsPanel() {
    }

    @Override
    public String getPanelTitle() {
        return "Aggregate";
    }

    @Override
    public DBPImage getPanelImage() {
        return UIIcon.APACHE;
    }

    @Override
    public String getPanelDescription() {
        return "Aggregate columns";
    }

    @Override
    public Control createContents(IResultSetPresentation presentation, Composite parent) {
        this.presentation = presentation;
        this.panelSettings = UIUtils.getSettingsSection(presentation.getController().getViewerSettings(), SETTINGS_SECTION_AGGREGATE);

        loadSettings();

        this.aggregateTable = new Tree(parent, SWT.SINGLE | SWT.FULL_SELECTION);
        this.aggregateTable.setHeaderVisible(true);
        this.aggregateTable.setLinesVisible(true);
        new TreeColumn(this.aggregateTable, SWT.LEFT).setText("Function");
        new TreeColumn(this.aggregateTable, SWT.RIGHT).setText("Value");

        if (this.presentation instanceof ISelectionProvider) {
            ((ISelectionProvider) this.presentation).addSelectionChangedListener(new ISelectionChangedListener() {
                @Override
                public void selectionChanged(SelectionChangedEvent event) {
                    refresh();
                }
            });
        }

        MenuManager menuMgr = new MenuManager();
        menuMgr.addMenuListener(new IMenuListener() {
            @Override
            public void menuAboutToShow(IMenuManager manager)
            {
                fillToolBar(manager);
            }
        });

        menuMgr.setRemoveAllWhenShown(true);
        this.aggregateTable.setMenu(menuMgr.createContextMenu(this.aggregateTable));

        aggregateTable.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                if (panelToolbar != null) {
                    panelToolbar.update(true);
                }
            }
        });

        return this.aggregateTable;
    }

    private void loadSettings() {
        groupByColumns = panelSettings.getBoolean(PARAM_GROUP_BY_COLUMNS);
        IDialogSettings functionsSection = panelSettings.getSection("functions");
        if (functionsSection != null) {
            for (IDialogSettings funcSection : functionsSection.getSections()) {
                String funcId = funcSection.getName();
                if (!funcSection.getBoolean("enabled")) {
                    continue;
                }
                AggregateFunctionDescriptor func = FunctionsRegistry.getInstance().getFunction(funcId);
                if (func == null) {
                    log.debug("Function '" + funcId + "' not found");
                } else {
                    enabledFunctions.add(func);
                }
            }
        }

        if (enabledFunctions.isEmpty()) {
            loadDefaultFunctions();
        }
    }

    private void loadDefaultFunctions() {
        for (AggregateFunctionDescriptor func : FunctionsRegistry.getInstance().getFunctions()) {
            if (func.isDefault()) {
                enabledFunctions.add(func);
            }
        }
        Collections.sort(enabledFunctions, new Comparator<AggregateFunctionDescriptor>() {
            @Override
            public int compare(AggregateFunctionDescriptor o1, AggregateFunctionDescriptor o2) {
                return o1.getLabel().compareTo(o2.getLabel());
            }
        });
    }

    private void saveSettings() {
        panelSettings.put(PARAM_GROUP_BY_COLUMNS, groupByColumns);
        IDialogSettings functionsSection = UIUtils.getSettingsSection(panelSettings, "functions");
        for (AggregateFunctionDescriptor func : enabledFunctions) {
            IDialogSettings funcSection = UIUtils.getSettingsSection(functionsSection, func.getId());
            funcSection.put("enabled", true);
        }
    }

    @Override
    public void activatePanel(IContributionManager contributionManager) {
        this.panelToolbar = contributionManager;
        fillToolBar(contributionManager);
        refresh();
    }

    @Override
    public void deactivatePanel() {

    }

    @Override
    public void refresh() {
        aggregateTable.removeAll();
        if (this.presentation instanceof ISelectionProvider) {
            ISelection selection = ((ISelectionProvider) presentation).getSelection();
            if (selection instanceof IResultSetSelection) {
                aggregateSelection((IResultSetSelection)selection);
            }
        }
        UIUtils.packColumns(aggregateTable, true, null);
        saveSettings();
    }

    private void aggregateSelection(IResultSetSelection selection) {
        List<AggregateFunctionDescriptor> functions = enabledFunctions;
        ResultSetModel model = presentation.getController().getModel();
        for (AggregateFunctionDescriptor funcDesc : functions) {
            try {
                int valueCount = 0;
                IAggregateFunction func = funcDesc.createFunction();
                for (Iterator iter = selection.iterator(); iter.hasNext(); ) {
                    Object element = iter.next();
                    DBDAttributeBinding attr = selection.getElementAttribute(element);
                    ResultSetRow row = selection.getElementRow(element);
                    Object cellValue = model.getCellValue(attr, row);
                    if (cellValue instanceof Number) {
                        func.accumulate((Number) cellValue);
                        valueCount++;
                    }
                }
                TreeItem funcItem = new TreeItem(aggregateTable, SWT.NONE);
                funcItem.setData(funcDesc);
                funcItem.setText(0, funcDesc.getLabel());
                funcItem.setImage(0, DBeaverIcons.getImage(funcDesc.getIcon()));
                if (valueCount > 0) {
                    Number result = func.getResult(valueCount);
                    if (result != null) {
                        funcItem.setText(1, result.toString());
                    }
                }
            } catch (Exception e) {
                log.error(e);
            }
        }
    }

    public void clearValue()
    {
        aggregateTable.removeAll();
    }

    private void fillToolBar(IContributionManager contributionManager)
    {
        contributionManager.add(new AddFunctionAction());
        contributionManager.add(new RemoveFunctionAction());
        contributionManager.add(new ResetFunctionsAction());
        contributionManager.add(new Separator());
        contributionManager.add(new GroupByColumnsAction());
    }

    private class GroupByColumnsAction extends Action {
        public GroupByColumnsAction() {
            super("Group by columns", IAction.AS_CHECK_BOX);
            setImageDescriptor(DBeaverIcons.getImageDescriptor(DBIcon.TREE_COLUMN));
        }

        @Override
        public boolean isChecked() {
            return groupByColumns;
        }

        @Override
        public void run() {
            groupByColumns = !groupByColumns;
            refresh();
        }
    }

    private class AddFunctionAction extends Action {
        public AddFunctionAction() {
            super("Add function", DBeaverIcons.getImageDescriptor(UIIcon.ROW_ADD));
        }

        @Override
        public void run() {
            List<AggregateFunctionDescriptor> missingFunctions = new ArrayList<>();
            for (AggregateFunctionDescriptor func : FunctionsRegistry.getInstance().getFunctions()) {
                if (!enabledFunctions.contains(func)) {
                    missingFunctions.add(func);
                }
            }
            if (!missingFunctions.isEmpty()) {
                Point location = aggregateTable.getDisplay().map(aggregateTable, null, new Point(0, 0));
                MenuManager menuManager = new MenuManager();

                for (final AggregateFunctionDescriptor func : missingFunctions) {
                    menuManager.add(new AddFunctionItemAction(func));
                }

                final Menu contextMenu = menuManager.createContextMenu(aggregateTable);
                contextMenu.setLocation(location);
                contextMenu.setVisible(true);
            }
        }
    }

    private class AddFunctionItemAction extends Action {
        private final AggregateFunctionDescriptor func;

        public AddFunctionItemAction(AggregateFunctionDescriptor func) {
            super(func.getLabel(), DBeaverIcons.getImageDescriptor(func.getIcon()));
            this.func = func;
        }

        @Override
        public void run() {
            enabledFunctions.add(func);
            refresh();
        }
    }

    private class RemoveFunctionAction extends Action {
        public RemoveFunctionAction() {
            super("Remove function", DBeaverIcons.getImageDescriptor(UIIcon.ROW_DELETE));
        }

        @Override
        public boolean isEnabled() {
            return aggregateTable.getSelectionCount() > 0;
        }

        @Override
        public void run() {
            for (TreeItem item : aggregateTable.getSelection()) {
                AggregateFunctionDescriptor func = (AggregateFunctionDescriptor) item.getData();
                enabledFunctions.remove(func);
            }
            refresh();
        }
    }

    private class ResetFunctionsAction extends Action {
        public ResetFunctionsAction() {
            super("Reset", DBeaverIcons.getImageDescriptor(UIIcon.RESET));
        }

        @Override
        public void run() {
            enabledFunctions.clear();
            loadDefaultFunctions();
            refresh();
        }
    }

}