package pl.pw.goegame

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.firestore.DocumentSnapshot
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class GameActivity: AppCompatActivity() {
    private val playerService = PlayerService()
    private val gameService = GameService()
    private val scope = MainScope()

    private lateinit var gameId: String
    private lateinit var mainPlayerId: String
    private lateinit var opponentId: String

    private lateinit var pageTitle: TextView
    private lateinit var rockBtn: ImageButton
    private lateinit var paperBtn: ImageButton
    private lateinit var scissorsBtn: ImageButton

    companion object {
        val TAG = "pw.GameActivity"
    }

    enum class RoundResult {
        DRAW, MAIN_PLAYER_WINS, OPPONENT_WINS
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_game)
        assignInfoFromIntent()
        setUpUI()
        checkPlayerIn()
        listenForGameChanges()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun setUpUI() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.game)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        pageTitle = findViewById(R.id.gameTitle)
        rockBtn = findViewById(R.id.btnRock)
        paperBtn = findViewById(R.id.btnPaper)
        scissorsBtn = findViewById(R.id.btnScissors)

        pageTitle.text = "Walczysz o pozostanie w grze z: \n$opponentId \nWybierz swój ruch:"

        rockBtn.setOnClickListener {
            makeMove("rock")
        }
        paperBtn.setOnClickListener {
            makeMove("paper")
        }
        scissorsBtn.setOnClickListener {
            makeMove("scissors")
        }

        enableMoveButtons(false)
    }

    private fun enableMoveButtons(enable: Boolean) {
        rockBtn.isEnabled = enable
        paperBtn.isEnabled = enable
        scissorsBtn.isEnabled = enable
    }

    private fun assignInfoFromIntent() {
        val extras = intent.extras
        if (extras != null) {
            gameId = extras.getString("GAME_ID").toString()
            mainPlayerId = extras.getString("PLAYER_ID").toString()
            opponentId = extras.getString("OPPONENT_ID").toString()
        }
    }

    private fun checkPlayerIn() {
        scope.launch {
            gameService.playerJoinedGame(gameId, mainPlayerId)
        }
    }

    private fun makeMove(move: String) {
        enableMoveButtons(false)
        scope.launch {
            gameService.updatePlayerMove(gameId, mainPlayerId, move)
        }
    }

    private fun listenForGameChanges() {
        val gamesDocumentRef = gameService.gamesCollection.document(gameId)
            .addSnapshotListener {snapshot, e ->
                if (e != null) {
                    Log.w(TAG, "Błąd podczas dodawania listenera w GameActivity: $gameId", e)
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    handleGameUpdate(snapshot)
                }
            }
    }

    private fun handleGameUpdate(snapshot: DocumentSnapshot) {
        val mainPlayerStatus = snapshot.getString("${mainPlayerId}Status")
        val opponentStatus = snapshot.getString("${opponentId}Status")
        val currentMainPlayerMove = snapshot.getString("${mainPlayerId}Move") ?: "idle"
        val currentOpponentMove = snapshot.getString("${opponentId}Move") ?: "idle"

        if (currentMainPlayerMove == "idle" || currentOpponentMove == "idle") {
            enableMoveButtons(currentMainPlayerMove == "idle")
            if (currentMainPlayerMove != "idle") {
                Toast.makeText(this, "Oczekiwanie na ruch gracza: $opponentId...", Toast.LENGTH_SHORT).show()
            }
        } else {
            enableMoveButtons(false)

            val result = determineRoundResult(currentMainPlayerMove, currentOpponentMove)

            when (result) {
                RoundResult.DRAW -> {
                    pageTitle.text = "Remis!\nWybierz ponownie:"
                    scope.launch {
                        gameService.updatePlayerMove(gameId, mainPlayerId, "idle")
                    }
                }
                RoundResult.MAIN_PLAYER_WINS -> {
                    pageTitle.text = "Brawo $mainPlayerId, wygrywasz rundę!\nGra zakończona."
                    showGameOverDialog(true)
                }
                RoundResult.OPPONENT_WINS -> {
                    pageTitle.text = "$opponentId wygrywa rundę!\nGra zakończona."
                    showGameOverDialog(false)
                }
            }
        }
    }

    private fun showGameOverDialog(playerWon: Boolean) {
        val dialogFragment = GameOverDialogFragment.newInstance(playerWon)
        dialogFragment.isCancelable = false
        dialogFragment.show(supportFragmentManager, "gameOverDialogTag")
    }

    private fun determineRoundResult(mainPlayerMove: String, opponentMove: String): RoundResult {
        return when {
            mainPlayerMove == opponentMove -> RoundResult.DRAW
            (mainPlayerMove == "rock" && opponentMove == "scissors") ||
                    (mainPlayerMove == "paper" && opponentMove == "rock") ||
                    (mainPlayerMove == "scissors" && opponentMove == "paper") -> RoundResult.MAIN_PLAYER_WINS
            else -> RoundResult.OPPONENT_WINS
        }
    }

    private fun returnToLoginActivity() {
        val intent: Intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun returnToMapActivity() {
        val intent = Intent(this, MapActivity::class.java)
        intent.putExtra("PLAYER_ID_EXTRA", mainPlayerId)
        val team = mainPlayerId.split("-")[1]
        intent.putExtra("PLAYER_TEAM_EXTRA", team)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    fun goToLoginPage() {
        cleanUp()
        returnToLoginActivity()
        finishAffinity()
    }

    fun goToMapActivity() {
        returnToMapActivity()
        finishAffinity()
    }

    private fun cleanUp() {
        scope.launch {
            gameService.removeGame(gameId)
            playerService.removePlayer(mainPlayerId)
        }
    }
}