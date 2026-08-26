package cn.bitloom.controller;

import cn.bitloom.agentic.model.ModelConfig;
import cn.bitloom.holder.DialogHolder;
import cn.bitloom.window.WindowManager;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.ResourceBundle;
import java.util.function.Consumer;

/**
 * 模型编辑对话框：添加/编辑模型配置。
 */
@Slf4j
@Component
@Scope("prototype")
@RequiredArgsConstructor
public class ModelEditDialogController implements Initializable, WindowManager.StageAware, DialogHolder {

    private final WindowManager windowManager;

    @FXML
    private TextField nameField;
    @FXML
    private TextField baseUrlField;
    @FXML
    private PasswordField apiKeyField;
    @FXML
    private TextField chatModelField;
    @FXML
    private TextField completionsPathField;

    @Getter
    private Stage stage;

    /** 编辑时原配置（null = 新增） */
    private ModelConfig original;

    /** 保存回调 */
    private Consumer<ModelConfig> onSave;

    @Override
    public double getWidth() {
        return 460;
    }

    @Override
    public double getHeight() {
        return 480;
    }

    @Override
    public void setStage(Stage stage) {
        this.stage = stage;
        stage.setMinWidth(420);
        // 去掉窗口标题栏标题（无边框内容型弹窗，仅保留关闭按钮）
        stage.setTitle("");
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        completionsPathField.setText("/v1/chat/completions");
    }

    public void configure(ModelConfig original, Consumer<ModelConfig> onSave) {
        this.original = original;
        this.onSave = onSave;
        if (original != null) {
            nameField.setText(original.name());
            baseUrlField.setText(original.baseUrl());
            apiKeyField.setText(original.apiKey());
            chatModelField.setText(original.chatModel());
            completionsPathField.setText(original.completionsPath());
        }
    }

    @FXML
    private void onConfirm() {
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        String baseUrl = baseUrlField.getText() == null ? "" : baseUrlField.getText().trim();
        if (name.isBlank() || baseUrl.isBlank()) {
            return;
        }
        String id = original != null ? original.id() : null;
        ModelConfig config = new ModelConfig(
                id,
                name,
                baseUrl,
                apiKeyField.getText() == null ? "" : apiKeyField.getText().trim(),
                chatModelField.getText() == null || chatModelField.getText().isBlank()
                        ? "deepseek-chat" : chatModelField.getText().trim(),
                completionsPathField.getText() == null || completionsPathField.getText().isBlank()
                        ? "/v1/chat/completions" : completionsPathField.getText().trim());
        if (onSave != null) {
            onSave.accept(config);
        }
        if (stage != null) {
            stage.close();
        }
    }

    @FXML
    private void onCancel() {
        if (stage != null) {
            stage.close();
        }
    }
}
