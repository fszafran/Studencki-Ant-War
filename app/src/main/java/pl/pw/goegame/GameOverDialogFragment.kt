package pl.pw.goegame

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.DialogFragment

class GameOverDialogFragment : DialogFragment() {

    companion object {
        const val ARG_PLAYER_WON = "player_won"

        fun newInstance(playerWon: Boolean): GameOverDialogFragment {
            val fragment = GameOverDialogFragment()
            val args = Bundle()
            args.putBoolean(ARG_PLAYER_WON, playerWon)
            fragment.arguments = args
            return fragment
        }
    }

    private lateinit var tvGameOverTitle: TextView
    private lateinit var tvGameOverMessage: TextView
    private lateinit var btnLogin: Button // Renamed for clarity based on XML text
    private lateinit var btnMap: Button // New button
    private lateinit var btnQuit: Button

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_game, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvGameOverTitle = view.findViewById(R.id.tvGameOverTitle)
        tvGameOverMessage = view.findViewById(R.id.tvGameOverMessage)
        btnLogin = view.findViewById(R.id.btnMainMenu) // Still uses btnMainMenu ID from XML
        btnMap = view.findViewById(R.id.btnMap)      // New button ID

        val playerWon = arguments?.getBoolean(ARG_PLAYER_WON, false) ?: false

        if (playerWon) {
            tvGameOverTitle.text = "Gratulacje, wygrana!"
            tvGameOverMessage.text = "Pokonałeś przeciwnika."
            btnMap.visibility = View.VISIBLE
        } else {
            tvGameOverTitle.text = "Koniec gry"
            tvGameOverMessage.text = "Zostałeś pokonany."
            btnMap.visibility = View.GONE
        }

        btnLogin.setOnClickListener {
            (activity as? GameActivity)?.goToLoginPage()
            dismiss()
        }

        btnMap.setOnClickListener {
            (activity as? GameActivity)?.goToMapActivity()
            dismiss()
        }
    }
}