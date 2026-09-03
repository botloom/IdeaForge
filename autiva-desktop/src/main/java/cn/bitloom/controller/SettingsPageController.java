package cn.bitloom.controller;

import cn.bitloom.agentic.model.ModelConfig;
import cn.bitloom.bridge.wechat.WechatILinkClient;
import cn.bitloom.holder.DialogHolder;
import cn.bitloom.node.svg.SvgImageView;
import cn.bitloom.vm.SettingsPageViewModel;
import cn.bitloom.window.WindowManager;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.URL;
import java.util.ResourceBundle;

@Slf4j
@RequiredArgsConstructor
public class SettingsPageController implements Initializable, WindowManager.StageAware, DialogHolder {

    private final SettingsPageViewModel viewModel;
    private final WechatILinkClient wechatILinkClient;
    private final WindowManager windowManager;

    @FXML
    private VBox settingsPage;
    @FXML
    private ImageView weixinQrImageView;
    @FXML
    private VBox weixinQrRow;
    @FXML
    private Label weixinQrHintLabel;
    @FXML
    private VBox weixinConnectedOverlay;
    @FXML
    private VBox weixinExpiredOverlay;
    @FXML
    private Button weixinRebindButton;
    @FXML
    private Button weixinRefreshButton;
    @FXML
    private PasswordField bochaApiKeyField;
    @FXML
    private VBox modelListContainer;

    private ChangeListener<WechatILinkClient.State> weixinStateListener;
    private Stage stage;

    @Override
    public double getWidth() {
        return 700;
    }

    @Override
    public double getHeight() {
        return 620;
    }

    @Override
    public boolean isResizable() {
        return true;
    }

    @Override
    public void setStage(Stage stage) {
        this.stage = stage;
        stage.setMinWidth(600);
        stage.setMinHeight(500);
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        this.bindViewModel();
    }

    /**
     * 弹窗打开时刷新：重新加载配置并刷新微信连接状态。
     */
    public void reload() {
        viewModel.loadFromStore();
        refreshModelList();
        updateWeixinStatus();
    }

    @FXML
    private void onSave() {
        viewModel.save();
        refreshModelList();
        if (stage != null) {
            stage.close();
        }
    }

    private void bindViewModel() {
        bochaApiKeyField.textProperty().bindBidirectional(viewModel.getBochaApiKey());
    }

    // ===== 模型管理 =====

    /**
     * 重建模型列表卡片：每个模型一行（名称+模型名，上移/下移/编辑/删除按钮），行间以分割线分隔。
     */
    private void refreshModelList() {
        modelListContainer.getChildren().clear();
        var models = viewModel.listModels();
        if (models.isEmpty()) {
            Label empty = new Label("暂无模型，点击上方“添加模型”新建");
            empty.getStyleClass().add("settings-page__row-subtitle");
            modelListContainer.getChildren().add(empty);
            return;
        }
        for (int i = 0; i < models.size(); i++) {
            HBox row = buildModelRow(models.get(i), i, models.size());
            if (i > 0) {
                row.getStyleClass().add("settings-page__model-row--divider");
            }
            modelListContainer.getChildren().add(row);
        }
    }

    private HBox buildModelRow(ModelConfig config, int index, int size) {
        VBox info = new VBox(2);
        Label name = new Label(config.name());
        name.getStyleClass().add("settings-page__row-title");
        Label detail = new Label(config.chatModel() + " · " + config.baseUrl());
        detail.getStyleClass().add("settings-page__row-subtitle");
        info.getChildren().addAll(name, detail);

        Button upBtn = buildMoveButton("/cn/bitloom/images/chevron-up.svg");
        upBtn.setDisable(index == 0);
        upBtn.setOnAction(e -> {
            viewModel.moveModel(config.id(), -1);
            refreshModelList();
        });

        Button downBtn = buildMoveButton("/cn/bitloom/images/chevron-down.svg");
        downBtn.setDisable(index == size - 1);
        downBtn.setOnAction(e -> {
            viewModel.moveModel(config.id(), 1);
            refreshModelList();
        });

        Button editBtn = new Button("编辑");
        editBtn.getStyleClass().add("settings-page__btn-secondary");
        editBtn.setOnAction(e -> openModelDialog(config));

        Button deleteBtn = new Button("删除");
        deleteBtn.getStyleClass().add("settings-page__btn-danger");
        deleteBtn.setOnAction(e -> {
            viewModel.deleteModel(config.id());
            refreshModelList();
        });

        HBox actions = new HBox(8, upBtn, downBtn, editBtn, deleteBtn);
        actions.setAlignment(Pos.CENTER_RIGHT);

        HBox row = new HBox(12, info, new Region(), actions);
        row.getStyleClass().add("settings-page__model-row");
        HBox.setHgrow(info, Priority.NEVER);
        HBox.setHgrow(row.getChildren().get(1), Priority.ALWAYS);
        return row;
    }

    /** 纯图标方形小按钮（上移/下移）。 */
    private Button buildMoveButton(String svgPath) {
        SvgImageView icon = new SvgImageView(svgPath);
        icon.setStrokeColor("#0071e3");
        icon.setFitWidth(14);
        icon.setFitHeight(14);
        Button btn = new Button();
        btn.getStyleClass().addAll("settings-page__btn-secondary", "settings-page__icon-btn");
        btn.setGraphic(icon);
        btn.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        return btn;
    }

    @FXML
    private void onAddModel() {
        openModelDialog(null);
    }

    /**
     * 打开模型编辑对话框（owner 为设置窗口；编辑回填后保存即生效）。
     */
    private void openModelDialog(ModelConfig original) {
        windowManager.showDialog("cn/bitloom/view/ModelEditDialog.fxml",
                settingsPage.getScene() == null ? null : settingsPage.getScene().getWindow(),
                (ModelEditDialogController ctrl) -> ctrl.configure(original,
                        config -> {
                            viewModel.saveModel(config);
                            refreshModelList();
                        }));
    }

    // ===== 微信绑定 =====

    private void updateWeixinStatus() {
        if (weixinStateListener != null) {
            try {
                WechatILinkClient oldClient = wechatILinkClient;
                oldClient.stateProperty().removeListener(weixinStateListener);
            } catch (Exception ignored) {
            }
            weixinStateListener = null;
        }

        try {
            WechatILinkClient client = wechatILinkClient;
            updateWeixinUi(client.getState(), client);

            weixinStateListener = (obs, oldVal, newVal) ->
                    Platform.runLater(() -> updateWeixinUi(newVal, client));
            client.stateProperty().addListener(weixinStateListener);

            weixinRebindButton.setOnAction(event -> handleRebind(client));
            weixinRefreshButton.setOnAction(event -> handleRefresh(client));
        } catch (Exception e) {
            weixinQrRow.setVisible(false);
            weixinQrRow.setManaged(false);
        }
    }

    private void updateWeixinUi(WechatILinkClient.State state, WechatILinkClient client) {
        weixinConnectedOverlay.setVisible(false);
        weixinExpiredOverlay.setVisible(false);

        switch (state) {
            case CONNECTED -> {
                weixinConnectedOverlay.setVisible(true);
                weixinQrHintLabel.setText("微信已绑定，点击重新加载可更换账号");
            }
            case CONNECTING -> {
                weixinQrHintLabel.setText("连接中...");
            }
            case DISCONNECTED -> {
                String qrContent = client.getQrCodeContent();
                if (qrContent != null) {
                    renderQrCode(qrContent);
                }
                weixinQrHintLabel.setText("扫码即可绑定微信");
            }
            case QR_EXPIRED -> {
                weixinExpiredOverlay.setVisible(true);
                weixinQrHintLabel.setText("二维码已过期，请点击重新加载");
            }
        }
    }

    private void handleRebind(WechatILinkClient client) {
        client.restartLogin();
    }

    private void handleRefresh(WechatILinkClient client) {
        client.startLogin();
    }

    private void renderQrCode(String content) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, 160, 160);
            int width = matrix.getWidth();
            int height = matrix.getHeight();
            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    pixels[y * width + x] = matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
                }
            }
            WritableImage fxImage = new WritableImage(width, height);
            fxImage.getPixelWriter().setPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
            weixinQrImageView.setImage(fxImage);
        } catch (WriterException e) {
            log.error("生成二维码失败", e);
        }
    }

}
