/******************************************************************************
* Copyright (C) 2011-2013  Fabio Zadrozny and others
*
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Eclipse Public License v1.0
* which accompanies this distribution, and is available at
* http://www.eclipse.org/legal/epl-v10.html
*
* Contributors:
*     Fabio Zadrozny <fabiofz@gmail.com>       - initial API and implementation
******************************************************************************/
package org.python.pydev.editor.codecompletion.revisited;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.python.pydev.core.ExtensionHelper;
import org.python.pydev.core.IInterpreterInfo;
import org.python.pydev.core.IInterpreterManager;
import org.python.pydev.plugin.PydevPlugin;
import org.python.pydev.shared_core.structure.DataAndImageTreeNode;
import org.python.pydev.shared_core.structure.OrderedSet;
import org.python.pydev.shared_core.structure.TreeNode;
import org.python.pydev.shared_ui.ImageCache;
import org.python.pydev.shared_ui.SharedUiPlugin;
import org.python.pydev.shared_ui.UIConstants;
import org.python.pydev.shared_ui.utils.RunInUiThread;
import org.python.pydev.ui.dialogs.SelectNDialog;
import org.python.pydev.ui.dialogs.TreeNodeLabelProvider;
import org.python.pydev.ui.pythonpathconf.IInterpreterInfoBuilder;
import org.python.pydev.ui.pythonpathconf.InterpreterInfo;

/**
 * This is a helper class to keep the PYTHONPATH of interpreters configured inside Eclipse with the PYTHONPATH that
 * the interpreter actually has currently.
 *
 * I.e.: doing on the command-line:
 *
 * d:\bin\Python265\Scripts\pip-2.6.exe install path.py --egg
 * d:\bin\Python265\Scripts\pip-2.6.exe uninstall path.py
 *
 * which will change the pythonpath should actually request a pythonpath update
 *
 *
 * Also, doing:
 *
 * d:\bin\Python265\Scripts\pip-2.6.exe install path.py
 * d:\bin\Python265\Scripts\pip-2.6.exe uninstall path.py
 *
 * which will only download the path.py without actually changing the pythonpath must also work!
 *
 * @author Fabio
 */
@SuppressWarnings({ "unchecked", "rawtypes" })
public class SynchSystemModulesManager {

    private static final boolean DEBUG = true;

    //    private static final long MIN_POLL_TIME = 1000 * 60;
    private static final long MIN_POLL_TIME = 1000 * 10; //TODO: Raise before final

    private long delta = 30 * 1000;

    private static class PythonpathChange {

        private String path;
        private boolean add;

        public PythonpathChange(String path, boolean add) {
            this.path = path;
            this.add = add;
        }

        @Override
        public String toString() {
            if (add) {
                return "Add to PYTHONPATH: " + path;
            }
            return "Remove from PYTHONPATH: " + path;
        }

        public void apply(OrderedSet<String> newPythonPath) {
            if (add) {
                newPythonPath.add(path);
            } else {
                newPythonPath.remove(path);
            }
        }
    }

    private final Job jobApplyChanges = new Job("Apply PYTHONPATH changes") {

        @Override
        protected IStatus run(IProgressMonitor monitor) {
            applySelectedChangesToInterpreterInfosPythonpath(root, selectElements, monitor);
            reschedule(); //reschedule the main job, not this one
            return Status.OK_STATUS;
        }

        private void applySelectedChangesToInterpreterInfosPythonpath(
                final DataAndImageTreeNode root, List<TreeNode> selectElements, IProgressMonitor monitor) {
            List<IInterpreterInfo> changedInfos = computeChanges(root, selectElements);

            if (changedInfos.size() > 0) {
                IInterpreterManager[] allInterpreterManagers = PydevPlugin
                        .getAllInterpreterManagers();
                for (IInterpreterManager manager : allInterpreterManagers) {

                    Map<String, IInterpreterInfo> map = new HashMap<>();
                    Set<String> changedNames = new HashSet<>();

                    for (IInterpreterInfo info : changedInfos) {
                        if (info.getInterpreterType() == manager.getInterpreterType()) {
                            map.put(info.getName(), info);
                            changedNames.add(info.getName());
                        } else {
                            synchInfoAndPythonpath(monitor, info);
                        }
                    }

                    if (changedNames.size() > 0) {
                        if (DEBUG) {
                            System.out.println("Updating interpreters: " + changedNames);
                        }
                        manager.setInfos(
                                map.values().toArray(new IInterpreterInfo[map.size()]),
                                changedNames,
                                null
                                );
                    }
                }
            }
        }
    };

    private final Job job = new Job("Synch System PYTHONPATH") {

        /**
         * Estimated time to do a full check on the differences.
         */

        @Override
        protected IStatus run(IProgressMonitor monitor) {
            long initialTime = System.currentTimeMillis();
            if (DEBUG) {
                System.out.println("SynchSystemModulesManager: job starting");
            }
            Thread thisThread = this.getThread();
            int initialPriority = Thread.NORM_PRIORITY;
            if (thisThread != null) {
                initialPriority = thisThread.getPriority();
                thisThread.setPriority(Thread.MIN_PRIORITY);
            }

            try {
                final DataAndImageTreeNode root = new DataAndImageTreeNode(null, null, null);
                boolean reschedulePolling = true;

                try {
                    Map<IInterpreterManager, Map<String, IInterpreterInfo>> managerToNameToInfo = new HashMap<>();
                    updateStructures(monitor, root, managerToNameToInfo);
                    delta = System.currentTimeMillis() - initialTime;
                    if (DEBUG) {
                        System.out.println("Full time to check single polling for changes in interpreters: " + delta
                                / 1000.0 + " secs.");
                    }

                    if (root.hasChildren()) {
                        reschedulePolling = false;
                        RunInUiThread.async(new Runnable() {

                            @Override
                            public void run() {
                                List<TreeNode> selectElements = selectElementsInDialog(root);

                                if (selectElements != null && selectElements.size() > 0) {
                                    jobApplyChanges.stack(root, selectElements);
                                }
                            }

                        });
                    } else {
                        //Ok, all is Ok in the PYTHONPATH, so, check if something changed inside the interpreter info
                        //and not on the PYTHONPATH.
                        Set<Entry<IInterpreterManager, Map<String, IInterpreterInfo>>> entrySet = managerToNameToInfo
                                .entrySet();
                        for (Entry<IInterpreterManager, Map<String, IInterpreterInfo>> entry : entrySet) {
                            for (Entry<String, IInterpreterInfo> entry2 : entry.getValue().entrySet()) {
                                synchInfoAndPythonpath(monitor, entry2.getValue());
                            }
                        }
                    }

                } finally {
                    if (reschedulePolling) {
                        reschedule();
                    }
                }

            } finally {
                if (thisThread != null) {
                    //As jobs are from a thread pool, restore the priority afterwards
                    thisThread.setPriority(initialPriority);
                }
            }
            return Status.OK_STATUS;
        }

    };

    /**
     * Here we'll update the tree structure to be shown to the user with the changes (root)
     * and the managerToNameToInfo structure with the information on the interpreter manager and related
     * interpreter infos.
     */
    private void updateStructures(IProgressMonitor monitor, final DataAndImageTreeNode root,
            Map<IInterpreterManager, Map<String, IInterpreterInfo>> managerToNameToInfo) {
        IInterpreterManager[] allInterpreterManagers = PydevPlugin.getAllInterpreterManagers();
        ImageCache imageCache = SharedUiPlugin.getImageCache();
        for (int i = 0; i < allInterpreterManagers.length; i++) {
            IInterpreterManager manager = allInterpreterManagers[i];

            Map<String, IInterpreterInfo> nameToInfo = new HashMap<>();
            managerToNameToInfo.put(manager, nameToInfo);

            IInterpreterInfo[] interpreterInfos = manager.getInterpreterInfos();
            for (int j = 0; j < interpreterInfos.length; j++) {
                IInterpreterInfo internalInfo = interpreterInfos[j];
                String executable = internalInfo.getExecutableOrJar();
                boolean askUser = false;
                IInterpreterInfo newInterpreterInfo = manager.createInterpreterInfo(executable, monitor,
                        askUser);

                OrderedSet<String> newEntries = new OrderedSet<String>(newInterpreterInfo.getPythonPath());
                newEntries.removeAll(internalInfo.getPythonPath());

                OrderedSet<String> removedEntries = new OrderedSet<String>(internalInfo.getPythonPath());
                removedEntries.removeAll(newInterpreterInfo.getPythonPath());

                if (newEntries.size() > 0 || removedEntries.size() > 0) {
                    DataAndImageTreeNode<IInterpreterInfo> interpreterNode = new DataAndImageTreeNode<IInterpreterInfo>(
                            root,
                            internalInfo,
                            imageCache.get(
                                    UIConstants.PY_INTERPRETER_ICON));

                    for (String s : newEntries) {
                        new DataAndImageTreeNode(interpreterNode, new PythonpathChange(s, true),
                                imageCache.get(UIConstants.LIB_SYSTEM));
                    }
                    for (String s : removedEntries) {
                        new DataAndImageTreeNode(interpreterNode, new PythonpathChange(s, false),
                                imageCache.get(UIConstants.REMOVE_LIB_SYSTEM));
                    }

                }
                nameToInfo.put(internalInfo.getName(), internalInfo);
            }
        }
    }

    /**
     * Given a passed tree, selects the elements on the tree (and returns the selected elements in a flat list).
     */
    private List<TreeNode> selectElementsInDialog(final DataAndImageTreeNode root) {
        List<TreeNode> selectElements = SelectNDialog.selectElements(root,
                new TreeNodeLabelProvider() {
                    @Override
                    public org.eclipse.swt.graphics.Image getImage(Object element) {
                        DataAndImageTreeNode n = (DataAndImageTreeNode) element;
                        return n.image;
                    };

                    @Override
                    public String getText(Object element) {
                        TreeNode n = (TreeNode) element;
                        Object data = n.getData();
                        if (data == null) {
                            return "null";
                        }
                        if (data instanceof IInterpreterInfo) {
                            IInterpreterInfo iInterpreterInfo = (IInterpreterInfo) data;
                            return iInterpreterInfo.getNameForUI();
                        }
                        return data.toString();
                    };
                },
                "System PYTHONPATH changes detected",
                "Please check which interpreters and paths should be updated.",
                true);
        return selectElements;
    }

    /**
     * Given the tree structure we created initially with all the changes (root) and the elements
     * that the user selected in the tree (selectElements), return a list of infos updated with the
     * proper pythonpath.
     */
    private List<IInterpreterInfo> computeChanges(final DataAndImageTreeNode root,
            List<TreeNode> selectElements) {
        List<IInterpreterInfo> changedInfos = new ArrayList<>();

        HashSet<TreeNode> set = new HashSet<TreeNode>(selectElements.size());
        set.addAll(selectElements);
        for (Object n : root.getChildren()) {
            DataAndImageTreeNode interpreterNode = (DataAndImageTreeNode) n;
            if (set.contains(interpreterNode)) {
                IInterpreterInfo info = (IInterpreterInfo) interpreterNode.getData();
                List<String> pythonPath = info.getPythonPath();

                boolean changed = false;
                OrderedSet<String> newPythonPath = new OrderedSet<String>(pythonPath);
                for (Object entryNode : interpreterNode.getChildren()) {
                    DataAndImageTreeNode pythonpathNode = (DataAndImageTreeNode) entryNode;
                    if (set.contains(pythonpathNode)) {
                        PythonpathChange change = (PythonpathChange) pythonpathNode.data;
                        change.apply(newPythonPath);
                        changed = true;
                    }
                }
                if (changed) {
                    InterpreterInfo copy = (InterpreterInfo) info.makeCopy();
                    copy.libs.clear();
                    copy.libs.addAll(newPythonPath);
                    changedInfos.add(copy);
                }
            }
        }
        return changedInfos;
    }

    private void synchInfoAndPythonpath(IProgressMonitor monitor, IInterpreterInfo internalInfo) {
        //If it was changed or not, we must check the internal structure too!
        IInterpreterInfoBuilder builder = (IInterpreterInfoBuilder) ExtensionHelper
                .getParticipant(
                        ExtensionHelper.PYDEV_INTERPRETER_INFO_BUILDER, false);
        builder.synchInfoToPythonPath(monitor, (InterpreterInfo) internalInfo);
    }

    private void reschedule() {
        long rescheduleMillis = delta;
        if (rescheduleMillis < MIN_POLL_TIME) {
            rescheduleMillis = MIN_POLL_TIME;
        }
        rescheduleMillis = MIN_POLL_TIME; //TODO: Remove before final
        if (DEBUG) {
            System.out.println("SynchSystemModulesManager: rescheduling in: " + rescheduleMillis / 1000.0 + " secs.");
        }
        job.schedule(rescheduleMillis); //Reschedule again for 30 seconds from now
    }

    public static void start() {
        if (DEBUG) {
            System.out.println("SynchSystemModulesManager: initial schedule");
        }

        //Should be called only once, at which point we'll start to check if things change in the pythonpath.
        synchManager.job.setPriority(Job.BUILD);
        synchManager.job.schedule(MIN_POLL_TIME);
    }

    //Singleton
    private static final SynchSystemModulesManager synchManager = new SynchSystemModulesManager();

    private SynchSystemModulesManager() {

    }
}