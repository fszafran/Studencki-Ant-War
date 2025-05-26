package pl.pw.goegame

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.tasks.await
import pl.pw.goegame.PlayerService.Companion

class GameService {
    private val db = Firebase.firestore
    val gamesCollection = db.collection("games")

    companion object {
        const val TAG = "FIREBASE"
    }

    suspend fun createGame(player1: Player, player2: Player): String {
        val gameId = "${player1.id}-vs-${player2.id}"
        val game = hashMapOf(
            "player1" to player1.id,
            "player2" to player2.id,
            "status" to "pending",
            "${player1.id}Status" to "pending",
            "${player2.id}Status" to "pending",
            "${player1.id}Move" to "idle",
            "${player2.id}Move" to "idle"
        )
        try {
            gamesCollection.document(gameId)
                .set(game)
                .await()
            Log.d(TAG, "Succesfully created game: $gameId")
            return gameId
        }
        catch (e: Exception) {
            Log.d(TAG, "Error while creating game $gameId")
            throw e
        }

    }

    suspend fun removeGame(gameId: String) {
        try {
            gamesCollection.document(gameId)
                .delete()
                .await()
        }
        catch (e: Exception) {
            Log.d(PlayerService.TAG, "Error deleting game $gameId: ", e)
            throw e
        }
    }

    suspend fun playerJoinedGame(gameId: String, playerId: String) {
        try {
            gamesCollection.document(gameId)
                .update("${playerId}Status", "in-game")
                .await()
            Log.d(TAG, "Successfully updated playerStatus: ${playerId}Status to in-game")
        }
        catch (e: Exception) {
            Log.d(TAG, "Error while updating playerStatus game ${playerId}Status")
            throw e
        }
    }

    suspend fun updatePlayerMove(gameId: String, playerId: String, move: String) {
        try {
            gamesCollection.document(gameId)
                .update("${playerId}Move", move)
                .await()
            Log.d(TAG, "Successfully updated playerMove: ${playerId}Move to $move")
        }
        catch (e: Exception) {
            Log.d(TAG, "Error while updating playerMove game ${playerId}Move")
            throw e
        }
    }

}