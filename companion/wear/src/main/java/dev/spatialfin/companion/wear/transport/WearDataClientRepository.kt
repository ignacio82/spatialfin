package dev.spatialfin.companion.wear.transport

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.spatialfin.companion.protocol.WearCredentials
import dev.spatialfin.companion.protocol.WearNextUpState
import dev.spatialfin.companion.protocol.WearNowPlayingState
import dev.spatialfin.companion.protocol.WearProtocolCodec
import dev.spatialfin.companion.protocol.WearProtocolPaths
import dev.spatialfin.companion.protocol.WearVitalsState
import dev.spatialfin.companion.wear.tiles.WearSurfaceUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WearDataClientRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val credentialsStore: WearCredentialsStore,
    private val surfaceUpdater: WearSurfaceUpdater,
) : DataClient.OnDataChangedListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _nowPlayingState = MutableStateFlow<WearNowPlayingState?>(null)
    val nowPlayingState: StateFlow<WearNowPlayingState?> = _nowPlayingState.asStateFlow()

    private val _vitalsState = MutableStateFlow<WearVitalsState?>(null)
    val vitalsState: StateFlow<WearVitalsState?> = _vitalsState.asStateFlow()

    private val _nextUpState = MutableStateFlow<WearNextUpState?>(null)
    val nextUpState: StateFlow<WearNextUpState?> = _nextUpState.asStateFlow()

    private val _coverArtBitmap = MutableStateFlow<Bitmap?>(null)
    val coverArtBitmap: StateFlow<Bitmap?> = _coverArtBitmap.asStateFlow()

    fun startListening() {
        Wearable.getDataClient(context).addListener(this)
        fetchInitialData()
    }

    fun stopListening() {
        Wearable.getDataClient(context).removeListener(this)
    }

    private fun fetchInitialData() {
        scope.launch {
            runCatching {
                val dataItems = Wearable.getDataClient(context).dataItems.await()
                try {
                    for (item in dataItems) {
                        runCatching { processDataItem(item) }.onFailure {
                            Timber.w(it, "WearDataClientRepository: failed to seed %s", item.uri)
                        }
                    }
                } finally {
                    dataItems.release()
                }
            }.onFailure {
                Timber.w(it, "WearDataClientRepository: failed to fetch initial data items")
            }
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            // One malformed item must not take the rest of the batch with it.
            runCatching {
                when (event.type) {
                    DataEvent.TYPE_CHANGED -> processDataItem(event.dataItem)
                    DataEvent.TYPE_DELETED -> {
                        if (event.dataItem.uri.path == WearProtocolPaths.PATH_STATE_NOW_PLAYING) {
                            _nowPlayingState.value = null
                            _coverArtBitmap.value = null
                        }
                    }
                    else -> Unit
                }
            }.onFailure {
                Timber.w(it, "WearDataClientRepository: failed to process %s", event.dataItem.uri)
            }
        }
    }

    private fun processDataItem(dataItem: com.google.android.gms.wearable.DataItem) {
        val path = dataItem.uri.path ?: return
        if (path !in HANDLED_PATHS) return
        val dataMap = DataMapItem.fromDataItem(dataItem).dataMap
        val payload = dataMap.getByteArray(WearProtocolPaths.DATA_KEY_PAYLOAD) ?: return

        when (path) {
            WearProtocolPaths.PATH_STATE_NOW_PLAYING -> {
                val state = runCatching { WearProtocolCodec.decodeNowPlaying(payload) }.getOrNull()
                _nowPlayingState.value = state
                Timber.d("WearDataClientRepository: updated now playing state for '%s'", state?.title)

                val asset = dataMap.getAsset(WearProtocolPaths.ASSET_KEY_COVER_ART)
                if (asset != null) loadCoverArtAsset(asset) else _coverArtBitmap.value = null
                surfaceUpdater.requestNowPlayingUpdate()
            }

            WearProtocolPaths.PATH_STATE_VITALS -> {
                val vitals = runCatching { WearProtocolCodec.decodeVitals(payload) }.getOrNull()
                _vitalsState.value = vitals
                Timber.d("WearDataClientRepository: updated vitals (battery=%d%%)", vitals?.batteryPercent)
                surfaceUpdater.requestVitalsUpdate()
            }

            WearProtocolPaths.PATH_STATE_NEXT_UP -> {
                val nextUp = runCatching { WearProtocolCodec.decodeNextUp(payload) }.getOrNull()
                _nextUpState.value = nextUp
                Timber.d("WearDataClientRepository: updated next up items (%d)", nextUp?.items?.size)
                surfaceUpdater.requestUpNextUpdate()
            }

            WearProtocolPaths.PATH_STATE_CREDENTIALS -> {
                val creds = runCatching { WearProtocolCodec.decodeCredentials(payload) }.getOrNull()
                if (creds != null) {
                    credentialsStore.saveCredentials(creds)
                    Timber.i("WearDataClientRepository: bootstrapped credentials for server %s", creds.serverName)
                }
            }
        }
    }

    private fun loadCoverArtAsset(asset: Asset) {
        scope.launch {
            runCatching {
                val fd = Wearable.getDataClient(context).getFdForAsset(asset).await()
                val inputStream = fd.inputStream
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream.close()
                fd.release()
                _coverArtBitmap.value = bitmap
                Timber.d("WearDataClientRepository: cover art bitmap loaded (%dx%d)", bitmap?.width, bitmap?.height)
            }.onFailure {
                Timber.w(it, "WearDataClientRepository: failed to load cover art asset")
            }
        }
    }

    private companion object {
        val HANDLED_PATHS = setOf(
            WearProtocolPaths.PATH_STATE_NOW_PLAYING,
            WearProtocolPaths.PATH_STATE_VITALS,
            WearProtocolPaths.PATH_STATE_NEXT_UP,
            WearProtocolPaths.PATH_STATE_CREDENTIALS,
        )
    }
}
