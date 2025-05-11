package pl.pw.goegame

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.firestore
import org.osmdroid.util.GeoPoint
import com.google.firebase.firestore.GeoPoint as FirestoreGeoPoint
import kotlinx.coroutines.tasks.await

class PlayerService {
    private val db = Firebase.firestore
    private val playersCollection = db.collection("players")
    private val gamesCollection = db.collection("games")

    companion object {
        const val TAG = "FIREBASE"
    }

    suspend fun addPlayer(playerName: String, team: String): String {
        val playerId = "$playerName-$team"
        val player = hashMapOf(
            "name" to playerName,
            "team" to team,
            "currentGameId" to "",
            "location" to null
        )

        try {
            playersCollection.document(playerId)
                .set(player)
                .await()
            Log.d(TAG, "ID $playerId successfully written.")
            return playerId
        } catch (e: Exception) {
            Log.w(TAG, "Error writing document with ID $playerId", e)
            throw e
        }
    }

    suspend fun updatePlayerPosition(player: Player) {
        val newPosition = player.location
        val playerId = player.id
        val firestoreLocation = newPosition?.let { FirestoreGeoPoint(it.latitude, newPosition.longitude) }
        try {
            playersCollection.document(playerId)
                .update("location", firestoreLocation)
                .await()
            Log.d(TAG, "Player $playerId location updated (via Coroutine).")
        } catch (e: Exception) {
            Log.w(TAG, "Error updating player $playerId location (via Coroutine).", e)
            throw e
        }
    }

    suspend fun getAllPlayers(): MutableList<Player> {
        try {
            val allPlayersSnapshot = playersCollection.get().await()
            val playerList = mutableListOf<Player>()

            for (document in allPlayersSnapshot.documents) {
                val id = document.id
                val team = document.getString("team") ?: "Geodeci"
                val locationFirestore = document.getGeoPoint("location")
                val location = locationFirestore?.let { GeoPoint(it.latitude, it.longitude) }
                playerList.add(Player(id = id, location = location, team = team, marker = null))
            }
            return playerList
        }   catch (e: Exception) {
            Log.d(TAG, "Error getting documents: ", e)
            throw e
        }
    }
}