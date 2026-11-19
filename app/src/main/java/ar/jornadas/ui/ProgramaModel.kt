package ar.jornadas.ui

import android.app.Application
import androidx.lifecycle.*
import ar.jornadas.Servicios
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.IOException

class ProgramaModel(app: Application, private val estado: SavedStateHandle) : AndroidViewModel(app) {
    private val servicios = Servicios(app)
    val mesa = servicios.mesa
    private val repo = servicios.agenda
    val charlas = repo.programa.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val dia = estado.getStateFlow("dia", 0)
    val tema = estado.getStateFlow("tema", "Todos")
    val fallo = MutableStateFlow(false)
    val huboDescarga = MutableStateFlow(repo.huboDescarga)
    val cargando = MutableStateFlow(false)

    init { recargar() }

    fun elegirDia(valor: Int) { estado["dia"] = valor }

    fun elegirTema(valor: String) { estado["tema"] = valor }

    fun recargar() {
        if (cargando.value) return
        cargando.value = true
        viewModelScope.launch {
            try {
                repo.sincronizar()
                huboDescarga.value = true
                fallo.value = false
            } catch (e: IOException) {
                fallo.value = true
            } finally {
                cargando.value = false
            }
        }
    }

    fun marcar(id: String) { viewModelScope.launch { repo.cambiarFavorita(id) } }
}
