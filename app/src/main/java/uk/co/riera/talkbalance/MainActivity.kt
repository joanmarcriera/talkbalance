package uk.co.riera.talkbalance

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import uk.co.riera.talkbalance.recording.RecordingController
import uk.co.riera.talkbalance.ui.TalkBalanceApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RecordingController.recoverInterrupted(this)
        setContent { TalkBalanceApp() }
    }
}
