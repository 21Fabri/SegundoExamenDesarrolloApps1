package ar.jornadas.data

import androidx.room.withTransaction

class AgendaRepository(private val api: ProgramaApi, private val base: Archivo) {
    private val dao = base.cartelera()
    val programa = dao.observar()

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
    }

    suspend fun cambiarFavorita(id: String) = dao.alternar(id)
}
