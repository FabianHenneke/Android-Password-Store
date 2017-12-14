package com.zeapo.pwdstore.autofill;

import android.app.Service;
import android.os.Build;
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
    public void onFillRequest(@NonNull FillRequest fillRequest, @NonNull CancellationSignal cancellationSignal, @NonNull FillCallback fillCallback) {
        Log.d(Constants.TAG, "onFillRequest");
    }

    @Override
    public void onSaveRequest(@NonNull SaveRequest saveRequest, @NonNull SaveCallback saveCallback) {
        Log.d(Constants.TAG, "onSaveRequest");
    }
}
