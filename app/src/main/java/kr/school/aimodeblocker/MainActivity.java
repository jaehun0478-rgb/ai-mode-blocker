package kr.school.aimodeblocker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
    private TextView classCodeText;
    private Button accessibilityButton;
    private boolean registrationDialogShowing = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        accessibilityStatusText = findViewById(R.id.accessibilityStatusText);
        classCodeText = findViewById(R.id.classCodeText);
        accessibilityButton = findViewById(R.id.accessibilityButton);

        Button centralButton = findViewById(R.id.toggleButton);
        Button oldPinButton = findViewById(R.id.changePinButton);
        TextView managedInfoText = findViewById(R.id.managedInfoText);

        centralButton.setEnabled(false);
        centralButton.setText("반별 중앙관리 모드");
        oldPinButton.setVisibility(View.GONE);
        managedInfoText.setVisibility(View.VISIBLE);
        accessibilityButton.setOnClickListener(v -> openAccessibilitySettings());

        if (!ClassRegistration.hasClassCode(this)) {
            showClassRegistration(ClassRegistration.consumeResetNotice(this));
        } else {
            RemotePolicyManager.maybeSync(this, true);
            scheduleRegistrationRecheck();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();

        if (!ClassRegistration.hasClassCode(this)) {
            showClassRegistration(ClassRegistration.consumeResetNotice(this));
        }
    }

    private void showClassRegistration(boolean wasReset) {
        if (isFinishing() || registrationDialogShowing) return;
        registrationDialogShowing = true;

        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("예: 4123");
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad / 2, pad, pad / 2);

        String message = wasReset
                ? "이 기기의 이전 반 등록이 선생님에 의해 초기화되었습니다. 새 학년/반의 고유반번호를 입력하세요."
                : "선생님이 알려준 숫자 4~6자리 고유반번호를 입력하세요. 등록 후 이 기기는 해당 반의 ON/OFF 설정만 적용받습니다.";

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(wasReset ? "새 반번호 등록" : "고유반번호 등록")
                .setMessage(message)
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("등록", null)
                .create();

        dialog.setOnShowListener(ignored ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    String code = ClassRegistration.normalize(input.getText().toString());

                    if (!ClassRegistration.isValid(code)) {
                        input.setError("숫자 4~6자리로 입력하세요.");
                        return;
                    }

                    if (!ClassRegistration.saveOnce(this, code)) {
                        input.setError("반번호 저장에 실패했습니다.");
                        return;
                    }

                    dialog.dismiss();
                    registrationDialogShowing = false;
                    Toast.makeText(this, "반번호 " + code + " 등록 완료", Toast.LENGTH_SHORT).show();
                    RemotePolicyManager.maybeSync(this, true);
                    refreshUi();

                    if (!AccessibilityUtils.isServiceEnabled(this)) {
                        openAccessibilitySettings();
                    }
                })
        );

        dialog.setOnDismissListener(ignored -> registrationDialogShowing = false);
        dialog.show();
    }

    private void scheduleRegistrationRecheck() {
        mainHandler.postDelayed(this::checkRegistrationAfterSync, 1500L);
        mainHandler.postDelayed(this::checkRegistrationAfterSync, 4000L);
    }

    private void checkRegistrationAfterSync() {
        if (isFinishing()) return;
        if (!ClassRegistration.hasClassCode(this)) {
            refreshUi();
            showClassRegistration(ClassRegistration.consumeResetNotice(this));
        } else {
            refreshUi();
        }
    }

    private void refreshUi() {
        String classCode = ClassRegistration.getClassCode(this);
        classCodeText.setText(classCode.isEmpty()
                ? "등록된 고유반번호 없음"
                : "고유반번호: " + classCode);

        if (!classCode.isEmpty()) RemotePolicyManager.maybeSync(this, false);

        boolean enabled = !classCode.isEmpty()
                && RemotePolicyManager.isRemoteBlockingEnabled(this);
        boolean accessibilityEnabled = AccessibilityUtils.isServiceEnabled(this);

        if (classCode.isEmpty()) {
            statusText.setText("● 반번호 등록 필요");
            statusText.setTextColor(Color.rgb(196, 121, 0));
        } else if (enabled && accessibilityEnabled) {
            statusText.setText("● 이 반 AI 차단 활성화됨");
            statusText.setTextColor(Color.rgb(25, 135, 84));
        } else if (enabled) {
            statusText.setText("● 이 반 차단 ON · 접근성 권한 필요");
            statusText.setTextColor(Color.rgb(196, 121, 0));
        } else {
            statusText.setText("○ 이 반 AI 차단 꺼짐");
            statusText.setTextColor(Color.rgb(180, 45, 45));
        }

        accessibilityStatusText.setText(accessibilityEnabled
                ? "접근성 감지 서비스: 켜짐"
                : "접근성 감지 서비스: 꺼짐 — 한 번 켜야 차단됩니다.");

        accessibilityButton.setVisibility(accessibilityEnabled ? View.GONE : View.VISIBLE);
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(this, "접근성 설정을 열 수 없습니다.", Toast.LENGTH_LONG).show();
        }
    }
}
