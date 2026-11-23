package ru.hl100.pult

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope

/** Keeps the controller (and its state) across screen rotation. */
class PultViewModel(application: Application) : AndroidViewModel(application) {
    val controller = PultController(
        scope = viewModelScope,
        store = Settings(application),
        client = NetworkClient,
        phoneName = Build.MODEL,
        refreshWidgets = { Widgets.refresh(application) },
    )
}
