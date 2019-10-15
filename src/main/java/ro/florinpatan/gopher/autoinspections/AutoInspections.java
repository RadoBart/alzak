package ro.florinpatan.gopher.autoinspections;

import com.intellij.analysis.AnalysisScope;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.ex.GlobalInspectionContextImpl;
import com.intellij.codeInspection.ex.InspectionManagerEx;
import com.intellij.codeInspection.ex.InspectionProfileImpl;
import com.intellij.ide.scratch.ScratchFileService;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.components.ProjectComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.profile.codeInspection.InspectionProjectProfileManager;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiManager;
import gnu.trove.THashSet;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

@State(
        name = "AutoInspections",
        storages = {@Storage(StoragePathMacros.WORKSPACE_FILE)}
)
public class AutoInspections implements ProjectComponent, StartupActivity {
    private final static Logger LOG = Logger.getInstance(AutoInspections.class);
    protected static final String AUTO_INSPECTIONS_DELAY = "auto.inspections.delay";
    protected static final int AUTO_INSPECTIONS_DELAY_DEFAULT = 1000;
    private Project myProject;
    protected int myDelayMillis;
    private AutoInspectionsWatcher myWatcher;
    private GlobalInspectionContextImpl myInspectionContext;

    public void runActivity(@NotNull Project project) {
        myProject = project;
        myDelayMillis = PropertiesComponent.getInstance(project).getInt(AUTO_INSPECTIONS_DELAY, AUTO_INSPECTIONS_DELAY_DEFAULT);
        myInspectionContext = ((InspectionManagerEx) InspectionManager.getInstance(myProject)).createNewGlobalContext();
        myWatcher = createWatcher(project);
        myWatcher.activate();
    }

    @Override
    public void projectClosed() {
        if (myInspectionContext != null) myInspectionContext = null;
        if (myWatcher != null) myWatcher.deactivate();
    }

    @NotNull
    protected AutoInspectionsWatcher createWatcher(Project project) {
        return new DelayedDocumentWatcher(project, myDelayMillis, this::runAnnotator, file -> {
            if (ScratchFileService.getInstance().getRootType(file) != null) {
                return false;
            }
            // I don't know why this is needed but where I copy-pasted this from said it is so it stays
            return FileEditorManager.getInstance(project).isFileOpen(file);
        });
    }

    private void runAnnotator(int modificationStamp, Set<VirtualFile> changedFiles) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (myWatcher.isUpToDate(modificationStamp)) {
                run(changedFiles);
            }
        }, ModalityState.any());
    }

    private void run(Set<VirtualFile> changedFiles) {
        Set<VirtualFile> changedDirs = new THashSet<>();
        PsiManager psiManager = PsiManager.getInstance(myProject);

        String profileName = "Go Only";
        InspectionProfileImpl inspectionProfile = InspectionProjectProfileManager.getInstance(myProject).getProfile(profileName, false);

        changedFiles.forEach(virtualFile -> {
            if (myProject.isDisposed() || !virtualFile.isValid()) return;
            if (virtualFile.isDirectory()) {
                changedDirs.add(virtualFile);
                return;
            }

            while (!virtualFile.isDirectory()) {
                virtualFile = virtualFile.getParent();
            }

            changedDirs.add(virtualFile);
        });

        Set<VirtualFile> allFiles = new THashSet<>();
        changedDirs.forEach(virtualDirectory -> {
            PsiDirectory psiDir = psiManager.findDirectory(virtualDirectory);
            if (psiDir == null) return;

            VirtualFile[] virtualFiles = virtualDirectory.getChildren();
            for (VirtualFile vFile : virtualFiles) {
                if (vFile.isDirectory()) continue;
                allFiles.add(vFile);
            }
        });

        AnalysisScope scope = new AnalysisScope(myProject, allFiles);
        scope.setSearchInLibraries(false);

        myInspectionContext.setExternalProfile(inspectionProfile);
        myInspectionContext.setCurrentScope(scope);
        myInspectionContext.doInspections(scope);
    }
}
