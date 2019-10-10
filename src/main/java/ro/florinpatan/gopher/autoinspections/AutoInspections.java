package ro.florinpatan.gopher.autoinspections;

import com.intellij.codeInsight.daemon.impl.DefaultHighlightVisitorBasedInspection;
import com.intellij.codeInspection.GlobalInspectionContext;
import com.intellij.codeInspection.InspectionManager;
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
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.newvfs.impl.VirtualDirectoryImpl;
import com.intellij.psi.PsiFile;
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

    public void runActivity(@NotNull Project project) {
        LOG.info("Project startup activity");
        myProject = project;
        myDelayMillis = PropertiesComponent.getInstance(project).getInt(AUTO_INSPECTIONS_DELAY, AUTO_INSPECTIONS_DELAY_DEFAULT);
        myWatcher = createWatcher(project);
        myWatcher.activate();
    }

    @Override
    public void projectClosed() {
        myWatcher.deactivate();
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
        LOG.info("Should run the annotator inspections now");
        //InspectionManager inspectionManager = InspectionManager.getInstance(myProject);
        //GlobalInspectionContext context = inspectionManager.createNewGlobalContext();

        Set<VirtualFile> changedDirs = new THashSet<>();

        changedFiles.forEach(virtualFile -> {
            if (myProject.isDisposed() || !virtualFile.isValid()) return;
            if (virtualFile.isDirectory()){
                changedDirs.add(virtualFile);
                return;
            }

            while (!virtualFile.isDirectory()) {
                virtualFile = virtualFile.getParent();
            }

            changedDirs.add(virtualFile);
            //return InspectionEngine.runInspectionOnFile(psiFile, new LocalInspectionToolWrapper(inspectionTool), context);
        });

        changedDirs.forEach(virtualFile -> {
            VirtualFile[] virtualFiles = virtualFile.getChildren();
            for (VirtualFile vFile : virtualFiles) {
                if (vFile.isDirectory()) continue;
                PsiFile psiFile = PsiManager.getInstance(myProject).findFile(vFile);
                if (psiFile == null) continue;
                DefaultHighlightVisitorBasedInspection.AnnotatorBasedInspection.runGeneralHighlighting(psiFile, true, true);
            }
            //AnalysisScope analysisScope = new AnalysisScope(psiDirectory);
        });

        //AnalysisScope analysisScope = new AnalysisScope(psiFile);
        //final Module module = virtualFile != null ? ModuleUtilCore.findModuleForFile(virtualFile, myProject) : null;
        /**/
    }
}
