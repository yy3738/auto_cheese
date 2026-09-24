package com.cheese.assistant.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * 对局记录：手动封存的一局。moveListJson 为完整棋谱
 * [{"m":"h2e2","f":走子前FEN,"a":落子后FEN},...]（org.json 序列化）。
 */
@Entity(tableName = "games")
data class GameEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long,
    /** 用户执方：true=红（开局按"屏幕下方=用户"判定，悬浮窗可覆盖） */
    @ColumnInfo(name = "user_is_red") val userIsRed: Boolean,
    /** 胜/负/和/未定（用户标注） */
    @ColumnInfo(name = "final_result") val finalResult: String = RESULT_UNSET,
    @ColumnInfo(name = "move_list_json") val moveListJson: String,
    /** reviewing / done */
    @ColumnInfo(name = "review_status") val reviewStatus: String = STATUS_REVIEWING,
    /** 复盘产出的用户行棋分析 JSON：[{fen,iccs,best,scoreCp,deltaCp,depth}] */
    @ColumnInfo(name = "review_json") val reviewJson: String? = null,
    @ColumnInfo(name = "blunder_count") val blunderCount: Int = 0,
    @ColumnInfo(name = "blunder_count_major") val blunderCountMajor: Int = 0,
) {
    companion object {
        const val RESULT_UNSET = "未定"
        const val RESULT_WIN = "胜"
        const val RESULT_LOSS = "负"
        const val RESULT_DRAW = "和"
        const val STATUS_REVIEWING = "reviewing"
        const val STATUS_DONE = "done"
    }
}

/**
 * 局面知识库（ADR-0003）：局面级跨对局积累，Zobrist 主键。
 * 评分与分差均为引擎原生"走子方视角"（正数 = 轮走方优），展示层再翻译。
 */
@Entity(tableName = "positions")
data class PositionEntity(
    @PrimaryKey val zobrist: Long,
    @ColumnInfo(name = "fen") val fen: String,
    @ColumnInfo(name = "best_move") val bestMove: String,
    /** 最佳走法评分（厘兵，走子方视角）；杀棋时为 null 且 mateIn 非空 */
    @ColumnInfo(name = "best_score_cp") val bestScoreCp: Int,
    @ColumnInfo(name = "mate_in") val mateIn: Int? = null,
    @ColumnInfo(name = "depth") val depth: Int,
    @ColumnInfo(name = "occurrences") val occurrences: Int = 1,
    /** 用户最近一次在该局面的实际选择（ICCS） */
    @ColumnInfo(name = "last_played") val lastPlayed: String? = null,
    /** 最近一次用户选择与最佳的分差（厘兵，≥0） */
    @ColumnInfo(name = "last_delta_cp") val lastDeltaCp: Int? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Dao
interface GameDao {
    @Insert
    suspend fun insert(game: GameEntity): Long

    @Query("UPDATE games SET final_result = :result WHERE id = :id")
    suspend fun setResult(id: Long, result: String)

    @Query("UPDATE games SET review_status = :status, review_json = :reviewJson, blunder_count = :blunders, blunder_count_major = :major WHERE id = :id")
    suspend fun finishReview(id: Long, status: String, reviewJson: String, blunders: Int, major: Int)

    @Query("SELECT * FROM games ORDER BY id DESC")
    fun allGames(): Flow<List<GameEntity>>

    @Query("SELECT * FROM games WHERE id = :id")
    suspend fun get(id: Long): GameEntity?
}

@Dao
interface PositionDao {

    @Query("SELECT * FROM positions WHERE zobrist = :hash")
    suspend fun get(hash: Long): PositionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: PositionEntity)

    @Query("SELECT COUNT(*) FROM positions")
    suspend fun count(): Int
}

@Database(entities = [GameEntity::class, PositionEntity::class], version = 1)
abstract class AppDb : RoomDatabase() {
    abstract fun gameDao(): GameDao
    abstract fun positionDao(): PositionDao

    companion object {
        @Volatile private var instance: AppDb? = null

        fun get(context: Context): AppDb = synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDb::class.java, "cheese.db"
            ).build().also { instance = it }
        }
    }
}
