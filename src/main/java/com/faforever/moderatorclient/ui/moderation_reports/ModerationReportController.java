package com.faforever.moderatorclient.ui.moderation_reports;

import com.faforever.commons.api.dto.ModerationReportStatus;
import com.faforever.commons.replay.ReplayDataParser;
import com.faforever.moderatorclient.api.FafApiCommunicationService;
import com.faforever.moderatorclient.api.domain.ModerationReportService;
import com.faforever.moderatorclient.ui.BanInfoController;
import com.faforever.moderatorclient.ui.Controller;
import com.faforever.moderatorclient.ui.PlatformService;
import com.faforever.moderatorclient.ui.UiService;
import com.faforever.moderatorclient.ui.ViewHelper;
import com.faforever.moderatorclient.ui.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.FileNotFoundException;
import java.net.URI;
import com.google.common.base.Strings;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.awt.*;
import java.awt.datatransfer.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.text.MessageFormat.format;

@Component
@Slf4j
@RequiredArgsConstructor
public class ModerationReportController implements Controller<Region> {
    public TableView tableViewMostReportedAccounts;
    public TextArea moderatorStatisticsTextArea;
    private final ObjectMapper objectMapper;
    private final ModerationReportService moderationReportService;
    private final UiService uiService;
    private final FafApiCommunicationService communicationService;
    private final PlatformService platformService;
    private final ObservableList<PlayerFX> reportedPlayersOfCurrentlySelectedReport = FXCollections.observableArrayList();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();
    public CheckBox FilterLogCheckBox;
    public CheckBox AutomaticallyLoadChatLogCheckBox;
    public Button CreateReportButton;
    public Button CopyReportTemplate;

    @Value("${faforever.vault.replay-download-url-format}")
    private String replayDownLoadFormat;

    public Region root;
    public ChoiceBox<ChooseableStatus> statusChoiceBox;
    public TextField playerNameFilterTextField;
    public TableView<ModerationReportFX> reportTableView;
    public Button editReportButton;
    public TableView<PlayerFX> reportedPlayerView;
    public TextArea chatLogTextArea;
    public Button CopyReportedUserID;
    public Button CopyChatLog;
    public Button CopyReportID;
    public Button CopyGameID;
    public Button StartReplay;

    private FilteredList<ModerationReportFX> filteredItemList;
    private ObservableList<ModerationReportFX> itemList;
    private ModerationReportFX currentlySelectedItemNotNull;

    @Override
    public SplitPane getRoot() {
        return (SplitPane) root;
    }

    public void CopyReportedUserID() {
        setSysClipboardText(CopyReportedUserID.getId());
    }

    public void CopyChatLog() {
        setSysClipboardText(CopyChatLog.getId());
    }

    public void CopyReportID() {
        setSysClipboardText(CopyReportID.getId() + ",");
    }

    public void CopyGameID() {
        setSysClipboardText(CopyGameID.getId());
    }

    public void CreateReportButton() throws IOException {
        String reportedUserId = CreateReportButton.getId();
        String url = "https://forum.faforever.com/search?term=" + reportedUserId + "&in=titles";
        String cmd = "cmd /c start chrome " + url;
        Runtime.getRuntime().exec(cmd);
    }

    public void StartReplay() throws IOException, InterruptedException {
        String replayId = StartReplay.getId();
        String replayUrl = "https://replay.faforever.com/" + replayId;
        Path tempFilePath = Files.createTempFile("faf_replay_", ".fafreplay");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(replayUrl))
                .build();

        httpClient.send(request, HttpResponse.BodyHandlers.ofFile(tempFilePath));

        String cmd = "cmd /c " + tempFilePath;
        Runtime.getRuntime().exec(cmd);
    }

    public void CopyReportTemplate() throws FileNotFoundException {
        String reportId = CopyReportID.getId();
        String gameId = CopyGameID.getId();
        String[] offender = CopyReportedUserID.getId().split(" ", 2);

        String content = new Scanner(new File("TemplateReport.txt")).useDelimiter("\\Z").next();
        //TODO refactor use method to get the values
        content = content.replace("%report_id%", reportId);
        content = content.replace("%game_id%", gameId);
        content = content.replace("%offender%", offender[0]);

        setSysClipboardText(content);
    }

    public void onRepeatedOffendersButton() {
        CompletableFuture<List<ModerationReportFX>> allReports = moderationReportService.getAllReports();

        allReports.thenAccept(this::acceptRepeatedOffenders);
    }

    private void acceptRepeatedOffenders(List<ModerationReportFX> reports) {
        Map<String, Long> offendersAwaitingReports = reports.stream()
                .filter(report -> report.getReportStatus().equals(ModerationReportStatus.AWAITING))
                .flatMap(report -> report.getReportedUsers().stream())
                .collect(Collectors.groupingBy(PlayerFX::getRepresentation, Collectors.counting()));

        Map<String, Long> sortedOffendersAwaitingReports = offendersAwaitingReports.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));

        List<Offender> offenders = sortedOffendersAwaitingReports.entrySet().stream().map(entry -> new Offender(entry.getKey(), entry.getValue())).collect(Collectors.toList());
        tableViewMostReportedAccounts.setItems(FXCollections.observableArrayList(offenders));
        tableViewMostReportedAccounts.setOnKeyPressed(event -> {
            if (event.isControlDown() && event.getCode() == KeyCode.C) {
                Offender selectedOffender = (Offender) tableViewMostReportedAccounts.getSelectionModel().getSelectedItem();
                if (selectedOffender != null) {
                    StringSelection stringSelection = new StringSelection(selectedOffender.getPlayer());
                    Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                    clipboard.setContents(stringSelection, null);
                }
            }
        });
    }

    public void onStatisticsModeratorButton() {
        CompletableFuture<List<ModerationReportFX>> allReports = moderationReportService.getAllReports();
        allReports.thenAccept(this::acceptStatisticsModerator);
    }

    private void acceptStatisticsModerator(List<ModerationReportFX> reports) {
        int awaitingReports = (int) reports.stream()
                .map(ModerationReportFX::getReportStatus)
                .filter(status -> status.equals(ModerationReportStatus.AWAITING))
                .count();
        int discardedReports = (int) reports.stream()
                .map(ModerationReportFX::getReportStatus)
                .filter(status -> status.equals(ModerationReportStatus.DISCARDED))
                .count();
        int processingReports = (int) reports.stream()
                .map(ModerationReportFX::getReportStatus)
                .filter(status -> status.equals(ModerationReportStatus.PROCESSING))
                .count();
        int completedReports = (int) reports.stream()
                .map(ModerationReportFX::getReportStatus)
                .filter(status -> status.equals(ModerationReportStatus.COMPLETED))
                .count();
        //TODO refactor - use 1 map
        Map<PlayerFX, Integer> moderatorReportCountsAll = reports.stream()
                .filter(r-> r.getLastModerator()!=null)
                .collect(Collectors.groupingBy(ModerationReportFX::getLastModerator, Collectors.summingInt(r -> 1)));
        Map<PlayerFX, Integer> moderatorReportCountsDiscarded = reports.stream()
                .filter(report -> report.getReportStatus().equals(ModerationReportStatus.DISCARDED) && report.getLastModerator()!=null)
                .collect(Collectors.groupingBy(ModerationReportFX::getLastModerator, Collectors.summingInt(r -> 1)));
        Map<PlayerFX, Integer> moderatorReportCountsCompleted = reports.stream()
                .filter(report -> report.getReportStatus().equals(ModerationReportStatus.COMPLETED) && report.getLastModerator()!=null)
                .collect(Collectors.groupingBy(ModerationReportFX::getLastModerator, Collectors.summingInt(r -> 1)));
        //TODO refactor - filter the above map
        List<Map.Entry<PlayerFX,Integer>> entriesModeratorReportCountsAll = new ArrayList<>(moderatorReportCountsAll.entrySet());
        entriesModeratorReportCountsAll.sort((o1, o2) -> o2.getValue().compareTo(o1.getValue()));

        List<Map.Entry<PlayerFX,Integer>> entriesModeratorReportCountsDiscarded = new ArrayList<>(moderatorReportCountsDiscarded.entrySet());
        entriesModeratorReportCountsDiscarded.sort((o1, o2) -> o2.getValue().compareTo(o1.getValue()));

        List<Map.Entry<PlayerFX,Integer>> entriesModeratorReportCountsCompleted = new ArrayList<>(moderatorReportCountsCompleted.entrySet());
        entriesModeratorReportCountsCompleted.sort((o1, o2) -> o2.getValue().compareTo(o1.getValue()));

        StringBuilder sb = new StringBuilder();
        sb.append("Total reports: \t").append(reports.size()).append("\n");
        sb.append("Completed reports: \t").append(completedReports).append("\n");
        sb.append("Awaiting reports: \t").append(awaitingReports).append("\n");
        sb.append("Processing reports: \t").append(processingReports).append("\n");
        sb.append("Discarded reports: \t").append(discardedReports).append("\n");
        sb.append("\nALL reports:").append("\n");
        for (Map.Entry<PlayerFX,Integer> entry : entriesModeratorReportCountsAll) {
            String moderator = entry.getKey().getRepresentation();
            sb.append("Moderator: \t\t").append(moderator).append(", Reports: ").append(entry.getValue()).append("\n");
        }
        sb.append("\nCOMPLETED reports:").append("\n");
        for (Map.Entry<PlayerFX,Integer> entry : entriesModeratorReportCountsCompleted) {
            String moderator = entry.getKey().getRepresentation();
            sb.append("Moderator: \t").append(moderator).append(", Reports: \t").append(entry.getValue()).append("\n");
        }
        sb.append("\nDISCARDED reports:").append("\n");
        for (Map.Entry<PlayerFX,Integer> entry : entriesModeratorReportCountsDiscarded) {
            String moderator = entry.getKey().getRepresentation();
            sb.append("Moderator: \t\t").append(moderator).append(", Reports: \t").append(entry.getValue()).append("\n");
        }
        moderatorStatisticsTextArea.setText(sb.toString());
    }

    public static class Offender {
        private final StringProperty player;
        private final LongProperty offenseCount;

        public Offender(String player, long offenseCount) {
            this.player = new SimpleStringProperty(player);
            this.offenseCount = new SimpleLongProperty(offenseCount);
        }

        public String getPlayer() {
            return player.get();
        }

        public StringProperty playerProperty() {
            return player;
        }

        public long getOffenseCount() {
            return offenseCount.get();
        }

        public LongProperty offenseCountProperty() {
            return offenseCount;
        }
    }

    @FXML
    public void initialize() {
        // report is selected
        statusChoiceBox.setItems(FXCollections.observableArrayList(ChooseableStatus.values()));
        statusChoiceBox.getSelectionModel().select(ChooseableStatus.AWAITING);
        editReportButton.disableProperty().bind(reportTableView.getSelectionModel().selectedItemProperty().isNull());
        itemList = FXCollections.observableArrayList();
        filteredItemList = new FilteredList<>(itemList);
        renewFilter();
        SortedList<ModerationReportFX> sortedItemList = new SortedList<>(filteredItemList);
        sortedItemList.comparatorProperty().bind(reportTableView.comparatorProperty());
        ViewHelper.buildModerationReportTableView(reportTableView, sortedItemList, this::showChatLog);
        statusChoiceBox.getSelectionModel().selectedItemProperty().addListener(observable -> renewFilter());
        playerNameFilterTextField.textProperty().addListener(observable -> renewFilter());
        reportTableView.getSelectionModel()
                .selectedItemProperty()
                .addListener((observable, oldValue, newValue) -> {
                    try {
                        reportedPlayersOfCurrentlySelectedReport.setAll(newValue.getReportedUsers());
                        currentlySelectedItemNotNull = newValue;
                        CopyReportID.setId(newValue.getId());
                        CopyReportID.setText("Report ID: " + newValue.getId());
                        CopyGameID.setId(newValue.getGame().getId());
                        CopyGameID.setText("Game ID: " + newValue.getGame().getId());
                        StartReplay.setId(CopyGameID.getId());
                        StartReplay.setText("Start Replay: " + CopyGameID.getId());

                        if (AutomaticallyLoadChatLogCheckBox.isSelected()) {
                            showChatLog(newValue);
                            log.debug("[LoadChatLog] log automatically loaded");
                        }
                        for (PlayerFX item : reportedPlayersOfCurrentlySelectedReport) {
                            log.debug("Selected report id - offenders: " + item.getRepresentation());
                            CopyReportedUserID.setId(item.getRepresentation());
                            CopyReportedUserID.setText(item.getRepresentation());
                            CreateReportButton.setId(StringUtils.substringBetween(item.getRepresentation(), " [id ", "]"));
                            CreateReportButton.setText("Create report for " + StringUtils.substringBetween(item.getRepresentation(), " [id ", "]"));
                        }
                    } catch (Exception ErrorSelectedReport) {
                        log.debug("Exception for selected report: ");
                        log.debug(String.valueOf(ErrorSelectedReport));
                        chatLogTextArea.setText("Game ID is invalid or missing.");
                        CopyChatLog.setText("Chat does not exist");
                        CopyChatLog.setId("");
                        CopyGameID.setText("Game ID does not exist");
                        CopyGameID.setId("");
                        StartReplay.setText("Replay does not exist");
                        StartReplay.setId("");
                        CreateReportButton.setText("no value / missing Game ID");
                        CreateReportButton.setId("");
                        CopyReportedUserID.setText("no value / missing Game ID");
                        CopyReportedUserID.setId("");
                    }
                });
        chatLogTextArea.setText("select a report first");
        ViewHelper.buildUserTableView(platformService, reportedPlayerView, reportedPlayersOfCurrentlySelectedReport, this::addBan,
                playerFX -> ViewHelper.loadForceRenameDialog(uiService, playerFX), communicationService);
    }

    public static void setSysClipboardText(String writeMe) {
        System.setProperty("java.awt.headless", "false");
        Clipboard clip = Toolkit.getDefaultToolkit().getSystemClipboard();
        Transferable tText = new StringSelection(writeMe);
        clip.setContents(tText, null);
    }

    private void addBan(PlayerFX accountFX) {
        BanInfoController banInfoController = uiService.loadFxml("ui/banInfo.fxml");
        BanInfoFX ban = new BanInfoFX();
        ban.setPlayer(accountFX);
        banInfoController.setBanInfo(ban);
        banInfoController.addPostedListener(banInfoFX -> onRefreshAllReports());
        Stage banInfoDialog = new Stage();
        banInfoDialog.setTitle("Apply new ban");
        banInfoDialog.setScene(new Scene(banInfoController.getRoot()));
        banInfoController.preSetReportId(currentlySelectedItemNotNull.getId());
        banInfoDialog.showAndWait();
    }

    private void renewFilter() {
        filteredItemList.setPredicate(moderationReportFx -> {
            String playerFilter = playerNameFilterTextField.getText().toLowerCase();
            if (!Strings.isNullOrEmpty(playerFilter)) {
                boolean reportedPlayerPositive = moderationReportFx.getReportedUsers().stream().anyMatch(accountFX -> accountFX.getLogin().toLowerCase().contains(playerFilter));
                boolean reporterPositive = moderationReportFx.getReporter().getLogin().toLowerCase().contains(playerFilter);
                if (!(reportedPlayerPositive || reporterPositive)) {
                    return false;
                }
            }
            ChooseableStatus selectedItemChoiceBox = statusChoiceBox.getSelectionModel().getSelectedItem();
            if (selectedItemChoiceBox.toString().equals("ALL")) return true;
            ModerationReportStatus moderationReportStatus = selectedItemChoiceBox.getModerationReportStatus();
            return moderationReportFx.getReportStatus() == moderationReportStatus;
        });
    }

    public void onRefreshAllReports() {
        onStatisticsModeratorButton();
        onRepeatedOffendersButton();
        moderationReportService.getAllReports().thenAccept(reportFxes -> Platform.runLater(() -> itemList.setAll(reportFxes))).exceptionally(throwable -> {
            log.error("error loading reports", throwable);
            return null;
        });
    }

    public void onEdit() {
        EditModerationReportController editModerationReportController = uiService.loadFxml("ui/edit_moderation_report.fxml");
        editModerationReportController.setModerationReportFx(reportTableView.getSelectionModel().getSelectedItem());
        editModerationReportController.setOnSaveRunnable(() -> Platform.runLater(this::onRefreshAllReports));
        //statusChoiceBox.setItems(FXCollections.observableArrayList(ChooseableStatus.values()));
        Stage newCategoryDialog = new Stage();
        newCategoryDialog.setTitle("Edit Report");
        newCategoryDialog.setScene(new Scene(editModerationReportController.getRoot()));
        newCategoryDialog.showAndWait();
    }

    private enum ChooseableStatus {
        ALL(null),
        AWAITING(ModerationReportStatus.AWAITING),
        PROCESSING(ModerationReportStatus.PROCESSING),
        COMPLETED(ModerationReportStatus.COMPLETED),
        DISCARDED(ModerationReportStatus.DISCARDED);

        private final ModerationReportStatus moderationReportStatus;

        ChooseableStatus(ModerationReportStatus moderationReportStatus) {
            this.moderationReportStatus = moderationReportStatus;
        }
        public ModerationReportStatus getModerationReportStatus() {
            return moderationReportStatus;
        }
    }

    @SneakyThrows
    private void showChatLog(ModerationReportFX report) {
        GameFX game = report.getGame();
        String header = format("CHAT LOG -- Report ID {0} -- Replay ID {1} -- Game \"{2}\"\n\n",
                report.getId(), game.getId(), game.getName());
        Path tempFilePath = Files.createTempFile(format("faf_replay_", game.getId()), "");
        try {
            String replayUrl = game.getReplayUrl(replayDownLoadFormat);
            log.info("Downloading replay from {} to {}", replayUrl, tempFilePath);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(replayUrl))
                    .build();

            HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(tempFilePath));
            if (response.statusCode() == 404) {
                log.debug("The requested resource was not found on the server");
                StartReplay.setText("Replay not available");
                CopyChatLog.setText("Chat log not available");
                chatLogTextArea.setText(header + format("Loading replay failed. The server is probably processing the replay file at the moment."));
            } else {
                log.debug("The request was successful - parsing replay");
            ReplayDataParser replayDataParser = new ReplayDataParser(tempFilePath, objectMapper);
            String chatLog = header + replayDataParser.getChatMessages().stream()
                    .map(message -> format("[{0}] from {1} to {2}: {3}",
                            DurationFormatUtils.formatDuration(message.getTime().toMillis(), "HH:mm:ss"),
                            message.getSender(), message.getReceiver(), message.getMessage()))
                    .collect(Collectors.joining("\n"));

            BufferedReader bufReader = new BufferedReader(new StringReader(chatLog));

            StringBuilder chatLogFiltered = new StringBuilder();
            String compileSentences = "Can you give me some mass, |Can you give me some energy, |" +
                    "Can you give me one Engineer, | to notify: | to allies: Sent Mass | to allies: Sent Energy |" +
                    " to allies: sent ";
            Pattern pattern = Pattern.compile(compileSentences);
            String chatLine;
            while((chatLine = bufReader.readLine()) != null) {
                Matcher matcher = pattern.matcher(chatLine);
                boolean matchFound = matcher.find();
                if(!matchFound && FilterLogCheckBox.isSelected()) {
                    chatLogFiltered.append(chatLine).append("\n");
                }
            }
            CopyChatLog.setId(chatLogFiltered.toString());
            CopyChatLog.setText("Copy Chat Log");
            chatLogTextArea.setText(chatLogFiltered.toString());
            }
        } catch (Exception e) {
            StartReplay.setText("Replay not available");
            CopyChatLog.setText("Chat log not available");
            chatLogTextArea.setText(header + format("Loading replay failed due to {0}: \n{1}", e, e.getMessage()));
        }
        Files.delete(tempFilePath);
    }
}
