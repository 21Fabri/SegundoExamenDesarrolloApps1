package ar.jornadas.data

import android.content.Context
import androidx.room.withTransaction

class AgendaRepository(private val api: ProgramaApi, private val base: Archivo, contexto: Context) {
    private val dao = base.cartelera()
    private val memoria = contexto.getSharedPreferences("agenda", Context.MODE_PRIVATE)
    val programa = dao.observar()

    var huboDescarga: Boolean
        get() = memoria.getBoolean("huboDescarga", false)
        private set(value) { memoria.edit().putBoolean("huboDescarga", value).apply() }

    suspend fun sincronizar() {
        val fichas = api.consultar().map {
            Charla(it.id, it.title, it.track, it.day, it.time, it.room, it.speaker, it.summary)
        }
        base.withTransaction {
            val favoritas = dao.idsFavoritas().toSet()
            if (fichas.isEmpty()) {
                dao.borrarTodas()
            } else {
                dao.borrarAusentes(fichas.map { it.id })
                dao.guardar(fichas.map { it.copy(favorita = it.id in favoritas) })
            }
        }
        huboDescarga = true
    }

    suspend fun cambiarFavorita(id: String) = dao.alternar(id)
}
