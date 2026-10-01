package kr.school.aimodeblocker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private TextView statusText;
    private TextView accessibilityStatusText;
    private Button toggleButton;
    private Button accessibilityButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        accessibilityStatusText = findViewById(R.id.accessibilityStatusText);
        toggleButton = findViewById(R.id.toggleButton);
        accessibilityButton = findViewById(R.id.accessibilityButton);
        Button changePinButton = findViewById(R.id.changePinButton);
        TextView managedInfoText = findViewById(R.id.managedInfoText);

        toggleButton.setOnClickListener(v ->
                Toast.makeText(this, "차단 설정은 중앙관리 사이트에서 변경합니다.", Toast.LENGTH_SHORT).show());
        accessibilityButton.setOnClickListener(v -> openAccessibilitySettings());
        changePinButton.setOnClickListener(v ->
                Toast.makeText(this, "교사용 PIN은 중앙관리 사이트에서 변경합니다.", Toast.LENGTH_SHORT).show());

        toggleButton.setEnabled(false);
        changePinButton.setEnabled(false);
        changePinButton.setVisibility(View.GONE);
        managedInfoText.setVisibility(View.VISIBLE);
        RemotePolicyManager.maybeSync(this, true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
    }

    private void refreshUi() {
        RemotePolicyManager.maybeSync(this, false);
        boolean enabled = BlockPreferences.isEnabled(this)
                && RemotePolicyManager.isRemoteBlockingEnabled(this);
        boolean accessibilityEnabled = AccessibilityUtils.isServiceEnabled(this);

        if (enabled && accessibilityEnabled) {
            statusText.setText("● 차단 활성화됨");
            statusText.setTextColor(Color.rgb(25, 135, 84));
        } else if (enabled) {
            statusText.setText("● 차단 ON · 권한 필요");
            statusText.setTextColor(Color.rgb(196, 121, 0));
        } else {
            statusText.setText("○ 차단 기능 꺼짐");
            statusText.setTextColor(Color.rgb(180, 45, 45));
        }

        accessibilityStatusText.setText(accessibilityEnabled
                ? "접근성 감지 서비스: 켜짐"
                : "접근성 감지 서비스: 꺼짐 — 한 번 켜야 차단됩니다.");

        toggleButton.setText("중앙관리 모드");
        accessibilityButton.setVisibility(accessibilityEnabled ? View.GONE : View.VISIBLE);
    }

    private void onToggleRequested() {
        String title = BlockPreferences.isEnabled(this) ? "차단 끄기" : "차단 켜기";
        showPinPrompt(title, "교사용 PIN을 입력하세요.", pin -> {
            if (!PinManager.verify(this, pin)) {
                Toast.makeText(this, "PIN이 올바르지 않습니다.", Toast.LENGTH_SHORT).show();
                return;
            }

            boolean next = !BlockPreferences.isEnabled(this);
            BlockPreferences.setEnabled(this, next);
            Toast.makeText(
                    this,
                    next ? "AI 모드 차단을 켰습니다." : "AI 모드 차단을 껐습니다.",
                    Toast.LENGTH_SHORT
            ).show();
            refreshUi();

            if (next && !AccessibilityUtils.isServiceEnabled(this)) {
                openAccessibilitySettings();
            }
        });
    }

    private void showInitialPinSetup() {
        final EditText first = createPinInput();

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("교사용 PIN 만들기")
                .setMessage("차단 기능 설정에 사용할 숫자 PIN(4~12자리)을 설정하세요.")
                .setView(first)
                .setCancelable(false)
                .setPositiveButton("다음", null)
                .create();

        dialog.setOnShowListener(ignored ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    String pin = first.getText().toString();
                    if (!PinManager.isValidFormat(pin)) {
                        first.setError("숫자 4~12자리로 입력하세요.");
                        return;
                    }
                    dialog.dismiss();
                    showPinConfirmation(pin);
                })
        );
        dialog.show();
    }

    private void showPinConfirmation(String firstPin) {
        final EditText confirm = createPinInput();

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("PIN 확인")
                .setMessage("같은 PIN을 한 번 더 입력하세요.")
                .setView(confirm)
                .setCancelable(false)
                .setPositiveButton("저장", null)
                .setNegativeButton("뒤로", (d, w) -> showInitialPinSetup())
                .create();

        dialog.setOnShowListener(ignored ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    String secondPin = confirm.getText().toString();
                    if (!firstPin.equals(secondPin)) {
                        confirm.setError("PIN이 일치하지 않습니다.");
                        return;
                    }
                    if (!PinManager.savePin(this, firstPin)) {
                        Toast.makeText(this, "PIN 저장에 실패했습니다.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    dialog.dismiss();
                    Toast.makeText(this, "교사용 PIN을 설정했습니다.", Toast.LENGTH_SHORT).show();
                    if (!AccessibilityUtils.isServiceEnabled(this)) {
                        openAccessibilitySettings();
                    }
                })
        );
        dialog.show();
    }

    private void showVerifyThenChangePin() {
        showPinPrompt("현재 PIN 확인", "현재 교사용 PIN을 입력하세요.", oldPin -> {
            if (!PinManager.verify(this, oldPin)) {
                Toast.makeText(this, "PIN이 올바르지 않습니다.", Toast.LENGTH_SHORT).show();
                return;
            }

            final EditText input = createPinInput();
            new AlertDialog.Builder(this)
                    .setTitle("새 PIN")
                    .setMessage("새 숫자 PIN(4~12자리)을 입력하세요.")
                    .setView(input)
                    .setPositiveButton("변경", (d, w) -> {
                        String pin = input.getText().toString();
                        if (!PinManager.isValidFormat(pin)) {
                            Toast.makeText(this, "숫자 4~12자리로 입력하세요.", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (PinManager.savePin(this, pin)) {
                            Toast.makeText(this, "PIN을 변경했습니다.", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, "PIN 변경에 실패했습니다.", Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton("취소", null)
                    .show();
        });
    }

    private void showPinPrompt(String title, String message, PinCallback callback) {
        final EditText input = createPinInput();
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setView(input)
                .setPositiveButton("확인", (d, w) -> callback.onPin(input.getText().toString()))
                .setNegativeButton("취소", null)
                .show();
    }

    private EditText createPinInput() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setSingleLine(true);
        input.setHint("PIN");
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad / 2, pad, pad / 2);
        return input;
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(this, "접근성 설정을 열 수 없습니다.", Toast.LENGTH_LONG).show();
        }
    }

    private interface PinCallback {
        void onPin(String pin);
    }
}
