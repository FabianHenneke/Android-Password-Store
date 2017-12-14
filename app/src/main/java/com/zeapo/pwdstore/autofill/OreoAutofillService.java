package com.zeapo.pwdstore.autofill;

import android.app.Service;
import android.app.assist.AssistStructure;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.service.autofill.FillCallback;
import android.service.autofill.FillRequest;
import android.service.autofill.SaveCallback;
import android.service.autofill.SaveRequest;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.util.Log;

@RequiresApi(api = Build.VERSION_CODES.O)
public class OreoAutofillService extends android.service.autofill.AutofillService {

    final class Constants {
        static final String TAG = "KeychainOreoAutofill";
    }

    @Override
    public void onFillRequest(@NonNull FillRequest request, @NonNull CancellationSignal cancellationSignal, @NonNull FillCallback callback) {
        Log.d(Constants.TAG, "onFillRequest(): called");
        cancellationSignal.setOnCancelListener(new CancellationSignal.OnCancelListener() {
            @Override
            public void onCancel() {
                Log.w(Constants.TAG, "onFillRequest(): cancellation not supported");
            }
        });

        if (request.getFillContexts().size() > 1) {
            Log.w(Constants.TAG, "onFillRequest(): multiple FillContexts not supported");
        }
        final AssistStructure structure = request.getFillContexts().get(request.getFillContexts().size() - 1).getStructure();
        final String packageName = structure.getActivityComponent().getPackageName();
        Log.d(Constants.TAG, String.format("onFillRequest(): packageName=%s", packageName));

        // Do not offer autofill for OpenKeychain (circular) or system UI
        if (packageName.equals("org.sufficientlysecure.keychain") || packageName.equals("com.android.systemui")) {
            callback.onSuccess(null);
        }

        final OreoAssistStructureParser parser = new OreoAssistStructureParser(getApplicationContext(), structure);
    }

    @Override
    public void onSaveRequest(@NonNull SaveRequest saveRequest, @NonNull SaveCallback saveCallback) {
        Log.d(Constants.TAG, "onSaveRequest: called");
    }
}
