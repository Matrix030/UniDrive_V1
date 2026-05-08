package edu.nyu.unidrive.client.ui;

import edu.nyu.unidrive.client.ClientRuntime;
import edu.nyu.unidrive.client.ClientRuntimeService;
import edu.nyu.unidrive.client.DefaultSyncServiceFactory;
import edu.nyu.unidrive.client.InstructorSyncServiceFactory;
import edu.nyu.unidrive.client.SyncServiceHandle;
import edu.nyu.unidrive.client.session.SessionConfig;
import edu.nyu.unidrive.client.session.UserRole;
import edu.nyu.unidrive.client.storage.FolderBootstrapService;
import edu.nyu.unidrive.client.storage.InstructorFolderBootstrapService;
import edu.nyu.unidrive.client.storage.InstructorWorkspace;
import edu.nyu.unidrive.client.storage.ReceivedStateRecord;
import edu.nyu.unidrive.client.storage.ReceivedStateRepository;
import edu.nyu.unidrive.client.storage.SyncStateRecord;
import edu.nyu.unidrive.client.storage.SyncStateRepository;
import edu.nyu.unidrive.client.storage.WorkspaceLayout;
import edu.nyu.unidrive.client.sync.ReceivedReconcileService;
import edu.nyu.unidrive.client.sync.SyncDashboardSnapshot;
import edu.nyu.unidrive.client.sync.SyncDashboardSnapshotService;
import edu.nyu.unidrive.common.workspace.CoursePath;
import edu.nyu.unidrive.common.workspace.MockCourseRegistry;
import edu.nyu.unidrive.common.workspace.MockCourseRegistry.Course;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

public final class DashboardScene {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault());
    private static final String VIEW_BUTTON_STYLE =
        "-fx-background-color: #f4f4f4; -fx-border-color: #c7c7c7; -fx-border-radius: 3; -fx-background-radius: 3;";
    private static final String VIEW_BUTTON_STYLE_ACTIVE =
        "-fx-background-color: #dbeafe; -fx-border-color: #2563eb; -fx-text-fill: #1e3a8a; -fx-font-weight: bold; -fx-border-radius: 3; -fx-background-radius: 3;";

    private final SessionConfig session;
    private final Runnable onLogout;
    private ClientRuntime studentRuntime;
    private SyncServiceHandle instructorSyncHandle;
    private Path workspaceRoot;
    private Path databasePath;
    private SyncStateRepository dashboardRepository;
    private ReceivedStateRepository receivedStateRepository;
    private SyncDashboardSnapshotService dashboardSnapshotService;
    private ScheduledExecutorService dashboardRefreshExecutor;
    private SyncDashboardSnapshot latestSnapshot;
    private Label pendingLabel;
    private Label uploadingLabel;
    private Label syncedLabel;
    private Label failedLabel;
    private Label refreshLabel;
    private TableView<DashboardRow> tableView;
    private View activeView = View.UPLOADS;
    private Button uploadsButton;
    private Button receivedButton;

    public DashboardScene(SessionConfig session, Runnable onLogout) {
        this.session = session;
        this.onLogout = onLogout;
    }

    public Scene build() {
        startRuntime();

        dashboardRepository = new SyncStateRepository(databasePath);
        receivedStateRepository = new ReceivedStateRepository(databasePath);
        dashboardSnapshotService = new SyncDashboardSnapshotService(dashboardRepository);

        pendingLabel = new Label();
        uploadingLabel = new Label();
        syncedLabel = new Label();
        failedLabel = new Label();
        refreshLabel = new Label("Status refresh pending...");
        tableView = createTableView();

        uploadsButton = new Button(session.role() == UserRole.STUDENT ? "My submissions" : "My published");
        receivedButton = new Button(session.role() == UserRole.STUDENT ? "Received from instructor" : "Submissions from students");
        uploadsButton.setFocusTraversable(false);
        receivedButton.setFocusTraversable(false);
        uploadsButton.setOnAction(event -> setView(View.UPLOADS));
        receivedButton.setOnAction(event -> setView(View.RECEIVED));
        HBox viewToggle = new HBox(12, uploadsButton, receivedButton);

        Button switchUserButton = new Button("Switch User");
        switchUserButton.setOnAction(event -> onLogout.run());

        HBox statusSummary = new HBox(16, pendingLabel, uploadingLabel, syncedLabel, failedLabel);

        VBox content = new VBox(
            12,
            new Label("University Drive (" + session.role().name().toLowerCase() + " mode)"),
            new Label("Workspace: " + workspaceRoot),
            new Label("User: " + session.userId()),
            new Label("Server: " + session.baseUrl()),
            new Label("Background sync: active"),
            statusSummary,
            refreshLabel,
            viewToggle,
            tableView
        );
        if (session.role() == UserRole.INSTRUCTOR) {
            content.getChildren().add(buildNewAssignmentBar());
        }
        content.getChildren().add(switchUserButton);
        VBox.setVgrow(tableView, Priority.ALWAYS);
        Scene scene = new Scene(content, 960, 560);

        applySnapshot(dashboardSnapshotService.loadSnapshot());
        setView(View.UPLOADS);
        startDashboardRefresh();
        return scene;
    }

    public void shutdown() {
        if (dashboardRefreshExecutor != null) {
            dashboardRefreshExecutor.shutdownNow();
            dashboardRefreshExecutor = null;
        }
        if (studentRuntime != null) {
            studentRuntime.close();
            studentRuntime = null;
        }
        if (instructorSyncHandle != null) {
            instructorSyncHandle.close();
            instructorSyncHandle = null;
        }
        dashboardSnapshotService = null;
        dashboardRepository = null;
    }

    private void startRuntime() {
        if (session.role() == UserRole.STUDENT) {
            studentRuntime = new ClientRuntimeService(new FolderBootstrapService(), new DefaultSyncServiceFactory())
                .start(session.workspaceDirectory(), session.userId(), session.baseUrl());
            workspaceRoot = studentRuntime.workspace().rootDirectory();
            databasePath = studentRuntime.workspace().databasePath();
        } else {
            InstructorWorkspace workspace = new InstructorFolderBootstrapService().bootstrap(session.workspaceDirectory());
            instructorSyncHandle = new InstructorSyncServiceFactory().create(workspace, session.baseUrl());
            instructorSyncHandle.start();
            workspaceRoot = workspace.rootDirectory();
            databasePath = workspace.databasePath();
        }
    }

    private HBox buildNewAssignmentBar() {
        MockCourseRegistry registry = new MockCourseRegistry();
        ChoiceBox<Course> courseChoice = new ChoiceBox<>(FXCollections.observableArrayList(registry.courses()));
        courseChoice.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Course course) {
                return course == null ? "" : course.slug() + " — " + course.displayName();
            }

            @Override
            public Course fromString(String s) {
                return null;
            }
        });
        if (!registry.courses().isEmpty()) {
            courseChoice.getSelectionModel().selectFirst();
        }

        TextField idField = new TextField();
        idField.setPromptText("assignment id (e.g. hw1)");

        Label statusLabel = new Label();

        Button createButton = new Button("Create assignment slot");
        createButton.setOnAction(event -> {
            Course course = courseChoice.getValue();
            String assignmentId = idField.getText() == null ? "" : idField.getText().trim();
            if (course == null) {
                statusLabel.setText("Choose a course.");
                return;
            }
            if (assignmentId.isEmpty()) {
                statusLabel.setText("Enter an assignment id.");
                return;
            }
            try {
                CoursePath coursePath = new CoursePath(registry.currentTerm(), course.slug(), assignmentId);
                WorkspaceLayout.ensureAssignmentSlot(workspaceRoot, coursePath, edu.nyu.unidrive.common.workspace.WorkspaceRole.INSTRUCTOR);
                idField.clear();
                statusLabel.setText("Created " + coursePath.toRelativePath() + " — drop a file into publish/ to publish.");
            } catch (RuntimeException exception) {
                statusLabel.setText("Failed: " + exception.getMessage());
            }
        });

        HBox bar = new HBox(8, new Label("New assignment:"), courseChoice, idField, createButton, statusLabel);
        return bar;
    }

    private TableView<DashboardRow> createTableView() {
        TableView<DashboardRow> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);

        TableColumn<DashboardRow, String> fileColumn = new TableColumn<>("File");
        fileColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().fileName()));

        TableColumn<DashboardRow, String> statusColumn = new TableColumn<>("Status");
        statusColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(nullToEmpty(cell.getValue().status())));

        TableColumn<DashboardRow, String> remoteIdColumn = new TableColumn<>("Remote ID");
        remoteIdColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(nullToEmpty(cell.getValue().remoteId())));

        TableColumn<DashboardRow, String> hashColumn = new TableColumn<>("SHA-256");
        hashColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(nullToEmpty(shortenHash(cell.getValue().sha256()))));

        TableColumn<DashboardRow, String> lastSyncedColumn = new TableColumn<>("Last Synced");
        lastSyncedColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(nullToEmpty(formatLastSynced(cell.getValue().lastSynced()))));

        table.getColumns().add(fileColumn);
        table.getColumns().add(statusColumn);
        table.getColumns().add(remoteIdColumn);
        table.getColumns().add(hashColumn);
        table.getColumns().add(lastSyncedColumn);
        return table;
    }

    private void setView(View view) {
        this.activeView = view;
        if (uploadsButton != null && receivedButton != null) {
            uploadsButton.setStyle(view == View.UPLOADS ? VIEW_BUTTON_STYLE_ACTIVE : VIEW_BUTTON_STYLE);
            receivedButton.setStyle(view == View.RECEIVED ? VIEW_BUTTON_STYLE_ACTIVE : VIEW_BUTTON_STYLE);
        }
        renderTable();
        refreshStatusSummary();
    }

    private void renderTable() {
        if (tableView == null) {
            return;
        }
        if (activeView == View.UPLOADS) {
            if (latestSnapshot == null) {
                return;
            }
            tableView.setItems(FXCollections.observableArrayList(mapSnapshotRows(latestSnapshot.rows())));
        } else if (session.role() == UserRole.STUDENT) {
            tableView.setItems(FXCollections.observableArrayList(loadReceivedRows(
                ReceivedReconcileService.SOURCE_ASSIGNMENTS,
                ReceivedReconcileService.SOURCE_FEEDBACK
            )));
        } else {
            tableView.setItems(FXCollections.observableArrayList(loadReceivedRows(
                ReceivedReconcileService.SOURCE_INSTRUCTOR_SUBMISSIONS
            )));
        }
    }

    private void startDashboardRefresh() {
        dashboardRefreshExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "unidrive-dashboard-refresh");
            thread.setDaemon(true);
            return thread;
        });
        dashboardRefreshExecutor.scheduleAtFixedRate(() -> {
            SyncDashboardSnapshot snapshot = dashboardSnapshotService.loadSnapshot();
            Platform.runLater(() -> applySnapshot(snapshot));
        }, 1, 1, TimeUnit.SECONDS);
    }

    private void applySnapshot(SyncDashboardSnapshot snapshot) {
        latestSnapshot = snapshot;
        refreshLabel.setText("Last refresh: " + TIME_FORMATTER.format(Instant.now()));
        renderTable();
        refreshStatusSummary();
    }

    private void refreshStatusSummary() {
        if (activeView == View.RECEIVED) {
            int receivedCount = tableView.getItems() == null ? 0 : tableView.getItems().size();
            pendingLabel.setText("Received: " + receivedCount);
            uploadingLabel.setVisible(false);
            uploadingLabel.setManaged(false);
            syncedLabel.setVisible(false);
            syncedLabel.setManaged(false);
            failedLabel.setVisible(false);
            failedLabel.setManaged(false);
            return;
        }

        if (latestSnapshot != null) {
            pendingLabel.setText("Pending: " + latestSnapshot.pendingCount());
            uploadingLabel.setText("Uploading: " + latestSnapshot.uploadingCount());
            syncedLabel.setText("Synced: " + latestSnapshot.syncedCount());
            failedLabel.setText("Failed: " + latestSnapshot.failedCount());
        }
        uploadingLabel.setVisible(true);
        uploadingLabel.setManaged(true);
        syncedLabel.setVisible(true);
        syncedLabel.setManaged(true);
        failedLabel.setVisible(true);
        failedLabel.setManaged(true);
    }

    private List<DashboardRow> mapSnapshotRows(List<SyncStateRecord> rows) {
        return rows.stream().map(record -> fromSyncStateRecord(workspaceRoot, record)).toList();
    }

    private static DashboardRow fromSyncStateRecord(Path root, SyncStateRecord record) {
        return new DashboardRow(displayName(root, record.localPath()), record.status().name(), record.remoteId(), record.sha256(), record.lastSynced());
    }

    private List<DashboardRow> loadReceivedRows(String... sources) {
        List<String> allowedSources = List.of(sources);
        return receivedStateRepository.findAll().stream()
            .filter(row -> allowedSources.contains(row.source()))
            .filter(row -> !isIgnoredReceivedFile(row.localPath()))
            .map(row -> fromReceivedStateRecord(workspaceRoot, row))
            .toList();
    }

    private static DashboardRow fromReceivedStateRecord(Path root, ReceivedStateRecord record) {
        return new DashboardRow(displayName(root, record.localPath()), record.status().name(), record.remoteId(), record.sha256(), record.lastSynced());
    }

    private static String displayName(Path root, Path absolute) {
        if (root != null && absolute != null) {
            try {
                return root.relativize(absolute).toString().replace('\\', '/');
            } catch (RuntimeException ignored) {
            }
        }
        return absolute == null ? "" : absolute.toString();
    }

    private boolean isIgnoredReceivedFile(Path path) {
        Path name = path.getFileName();
        return name != null && "desktop.ini".equalsIgnoreCase(name.toString());
    }

    private String shortenHash(String sha256) {
        if (sha256 == null || sha256.isBlank()) {
            return "";
        }
        return sha256.length() <= 12 ? sha256 : sha256.substring(0, 12) + "...";
    }

    private String formatLastSynced(long lastSynced) {
        if (lastSynced <= 0L) {
            return "";
        }
        return TIME_FORMATTER.format(Instant.ofEpochMilli(lastSynced));
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private enum View {
        UPLOADS,
        RECEIVED
    }

    private record DashboardRow(
        String fileName,
        String status,
        String remoteId,
        String sha256,
        long lastSynced
    ) {
    }
}
