package com.iacomthiago.levanta

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Toca o alarme e conduz as fases do despertar:
 *
 *   RINGING -> (missão) -> WAITING -> RECHECK -> fim
 *                                        \-> sem resposta -> RELAPSE -> (missão) -> WAITING ...
 *
 * Não existe comando de "parar": o som só cessa quando a missão é cumprida,
 * numa soneca permitida, ou depois de [RING_TIMEOUT_MS] sem ninguém atender.
 */
class AlarmService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_EVENT = "event"
        const val ACTION_RESUME = "resume"
        const val ACTION_MISSION_DONE = "mission_done"
        const val ACTION_SNOOZE = "snooze"
        const val ACTION_RECHECK_OK = "recheck_ok"

        /** Se ninguém atender nesse tempo, o alarme desiste e registra a falha. */
        const val RING_TIMEOUT_MS = 20 * 60_000L

        /** As missões que não são de levantar tocam por esse tempo e viram um lembrete. */
        const val MISSION_TIMEOUT_MS = 3 * 60_000L

        /** Volume crescente: tempo do primeiro toque até o teto do volume. */
        const val RAMP_MS = 60_000L

        /** Volume crescente: fração do volume em que o som começa (bem baixinho). */
        const val RAMP_START = 0.02f

        /** Tempo para responder "estou de pé" antes de o alarme voltar. */
        const val RECHECK_WINDOW_MS = 45_000L

        /** Nos alarmes de teste, soneca e checagem levam só alguns segundos. */
        const val TEST_DELAY_MS = 20_000L

        private const val TAG = "Levanta"
        private const val CHANNEL = "alarme"
        private const val NOTIFICATION_ID = 42
        private const val SOUND_NONE = 0
        private const val SOUND_ALARM = 1
        private const val SOUND_CHIME = 2
        private const val SOUND_MUSIC = 3

        fun send(context: Context, action: String, extras: Intent.() -> Unit = {}) {
            val intent = Intent(context, AlarmService::class.java).setAction(action).apply(extras)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Não foi possível iniciar o serviço do alarme", e)
            }
        }

        /** Retoma um despertar que ficou pela metade (app fechado, aparelho reiniciado). */
        fun resumeIfNeeded(context: Context) {
            val s = Repo.session ?: return
            val now = System.currentTimeMillis()
            if (s.type != Tipo.LEVANTAR) {
                // Toque de missão: se ainda está no prazo volta a tocar; senão some sem registro.
                if (s.phase == Phase.RINGING && now - s.phaseSince <= MISSION_TIMEOUT_MS) {
                    send(context, ACTION_RESUME)
                } else {
                    Repo.dropSession()
                }
                return
            }
            if (now - s.phaseSince > 3 * 60 * 60_000L) {
                // Velho demais para retomar: fecha como não atendido.
                Repo.finishSession(Outcome.NO_ANSWER)
                return
            }
            when (s.phase) {
                Phase.RINGING, Phase.RELAPSE, Phase.RECHECK -> send(context, ACTION_RESUME)
                Phase.SNOOZED, Phase.WAITING ->
                    if (s.nextEventAt <= now) send(context, ACTION_EVENT)
                    else Scheduler.scheduleEvent(context, s.nextEventAt)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private var player: MediaPlayer? = null
    private var currentSound = SOUND_NONE
    private var savedVolume = -1
    private var wakeLock: PowerManager.WakeLock? = null
    private var ticking = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!ticking) return
            tick()
            if (ticking) handler.postDelayed(this, 500L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        audio = getSystemService(AudioManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_RESUME

        when (action) {
            ACTION_START -> if (intent != null) onAlarm(intent)
            ACTION_EVENT -> onEvent()
            ACTION_MISSION_DONE -> onMissionDone()
            ACTION_SNOOZE -> onSnooze()
            ACTION_RECHECK_OK -> onRecheckOk()
        }

        // Todo início por startForegroundService precisa virar serviço em
        // primeiro plano em poucos segundos, mesmo que vá parar logo depois.
        // Vem depois das transições para a notificação já sair com o texto certo.
        goForeground()

        val wakeUi = action == ACTION_START || action == ACTION_EVENT || action == ACTION_RESUME
        applyPhase(showUi = wakeUi, phaseChanged = false)
        return START_STICKY
    }

    override fun onDestroy() {
        stopTicker()
        stopSound()
        super.onDestroy()
    }

    // ---------- transições ----------

    private fun onAlarm(intent: Intent) {
        val now = System.currentTimeMillis()
        val test = intent.getBooleanExtra("test", false)
        val alarmId = intent.getIntExtra("alarmId", -1)
        val alarm = Repo.alarms.firstOrNull { it.id == alarmId }
        val tipo = alarm?.type ?: Tipo.LEVANTAR

        val existing = Repo.session
        if (existing != null) {
            if (existing.type != Tipo.LEVANTAR) {
                // Um toque de missão nunca segura outro alarme, muito menos o de levantar.
                Repo.dropSession()
            } else if (now - existing.phaseSince < 60 * 60_000L) {
                // Já existe um despertar em andamento: o novo toque não o substitui,
                // a não ser que o antigo tenha ficado preso há mais de uma hora.
                // Uma missão que cairia no meio do despertar vira só um lembrete.
                if (!test && alarm != null && alarm.enabled && tipo != Tipo.LEVANTAR) {
                    Lembrete.missaoEsperando(this, alarm)
                }
                return
            } else {
                Repo.finishSession(if (existing.upAt > 0L) Outcome.OK else Outcome.NO_ANSWER)
            }
        }

        if (!test && (alarm == null || !alarm.enabled || !alarm.toca)) return
        // Missão já concluída hoje não precisa tocar.
        if (!test && alarm != null && tipo != Tipo.LEVANTAR && Jogo.feita(alarm)) return

        Repo.quietUntil = 0L
        Repo.lastResult = null
        Repo.updateSession(
            Session(
                alarmId = alarmId,
                label = when {
                    tipo != Tipo.LEVANTAR -> alarm?.nome.orEmpty()
                    test -> "Teste"
                    else -> alarm?.label.orEmpty()
                },
                test = test,
                scheduledAt = intent.getLongExtra("scheduledAt", now),
                startedAt = now,
                phase = Phase.RINGING,
                phaseSince = now,
                type = tipo,
            )
        )
    }

    private fun onEvent() {
        val s = Repo.session ?: return
        val now = System.currentTimeMillis()
        when (s.phase) {
            Phase.SNOOZED -> Repo.updateSession(s.copy(phase = Phase.RINGING, phaseSince = now))
            Phase.WAITING -> Repo.updateSession(
                s.copy(phase = Phase.RECHECK, phaseSince = now, nextEventAt = now + RECHECK_WINDOW_MS)
            )
        }
    }

    private fun onMissionDone() {
        val s = Repo.session ?: return
        if (!Phase.isLoud(s.phase) || s.type != Tipo.LEVANTAR) return
        val now = System.currentTimeMillis()
        val up = if (s.upAt == 0L) now else s.upAt
        Repo.quietUntil = 0L
        if (s.rechecksDone < Repo.config.recheckCount) {
            waitForRecheck(s.copy(upAt = up), now)
        } else {
            Repo.updateSession(s.copy(upAt = up))
            Repo.finishSession(Outcome.OK)
        }
    }

    private fun onSnooze() {
        val s = Repo.session ?: return
        if (s.type != Tipo.LEVANTAR) return
        if (s.phase != Phase.RINGING || s.snoozesUsed >= Repo.config.maxSnoozes) return
        val now = System.currentTimeMillis()
        val delay = if (s.test) TEST_DELAY_MS else Repo.config.snoozeMinutes * 60_000L
        Repo.updateSession(
            s.copy(
                phase = Phase.SNOOZED,
                phaseSince = now,
                snoozesUsed = s.snoozesUsed + 1,
                nextEventAt = now + delay,
            )
        )
        Scheduler.scheduleEvent(this, now + delay)
    }

    private fun onRecheckOk() {
        val s = Repo.session ?: return
        if (s.phase != Phase.RECHECK) return
        val now = System.currentTimeMillis()
        val done = s.rechecksDone + 1
        if (done < Repo.config.recheckCount) {
            waitForRecheck(s.copy(rechecksDone = done), now)
        } else {
            Repo.updateSession(s.copy(rechecksDone = done))
            Repo.finishSession(Outcome.OK)
        }
    }

    private fun waitForRecheck(s: Session, now: Long) {
        val delay = if (s.test) TEST_DELAY_MS else Repo.config.recheckMinutes * 60_000L
        Repo.updateSession(s.copy(phase = Phase.WAITING, phaseSince = now, nextEventAt = now + delay))
        Scheduler.scheduleEvent(this, now + delay)
    }

    /** Roda duas vezes por segundo enquanto há som. */
    private fun tick() {
        val s = Repo.session
        if (s == null) {
            applyPhase(showUi = false, phaseChanged = true)
            return
        }
        val now = System.currentTimeMillis()
        when (s.phase) {
            Phase.RINGING, Phase.RELAPSE -> {
                if (s.type != Tipo.LEVANTAR) {
                    if (now - s.phaseSince > MISSION_TIMEOUT_MS) {
                        // Ninguém atendeu: o som para e fica um lembrete da missão.
                        val alarme = Repo.alarms.firstOrNull { it.id == s.alarmId }
                        Repo.dropSession()
                        if (alarme != null && !s.test) Lembrete.missaoEsperando(this, alarme)
                        applyPhase(showUi = false, phaseChanged = true)
                        return
                    }
                } else if (now - s.phaseSince > RING_TIMEOUT_MS) {
                    Repo.finishSession(Outcome.NO_ANSWER)
                    applyPhase(showUi = false, phaseChanged = true)
                    return
                }
                // Acabou o tempo da música: entra o bipe, sem trégua de estilo.
                startSound(loudSound(s, now))
                keepLoud(s, now)
            }

            Phase.RECHECK -> {
                if (now >= s.nextEventAt) {
                    // Não respondeu: recaída. Volta a tocar e exige a missão de novo.
                    Repo.updateSession(
                        s.copy(phase = Phase.RELAPSE, phaseSince = now, relapses = s.relapses + 1)
                    )
                    applyPhase(showUi = true, phaseChanged = true)
                    return
                }
                setStreamVolume(minOf(0.6f, teto()))
            }

            else -> applyPhase(showUi = false, phaseChanged = true)
        }
    }

    /** Faz o que a fase atual pede: tocar, só avisar, ou encerrar o serviço. */
    private fun applyPhase(showUi: Boolean, phaseChanged: Boolean) {
        val s = Repo.session
        when (s?.phase) {
            Phase.RINGING, Phase.RELAPSE -> {
                startSound(loudSound(s ?: return))
                startTicker()
                if (phaseChanged) refreshNotification()
                if (showUi) launchUi()
            }

            Phase.RECHECK -> {
                startSound(SOUND_CHIME)
                startTicker()
                if (phaseChanged) refreshNotification()
                if (showUi) launchUi()
            }

            else -> {
                // Soneca, espera da checagem ou fim: nada toca, o serviço encerra.
                stopTicker()
                stopSound()
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    // ---------- som ----------

    private fun startSound(kind: Int) {
        if (currentSound == kind && player != null) return
        releasePlayer()
        if (savedVolume < 0) {
            savedVolume = try {
                audio.getStreamVolume(AudioManager.STREAM_ALARM)
            } catch (e: Exception) {
                -1
            }
        }
        // Já começa dentro do teto: sem isso, o primeiro meio segundo sairia no
        // volume em que o aparelho estava.
        setStreamVolume(if (kind == SOUND_CHIME) minOf(0.6f, teto()) else teto())
        try {
            val recurso = when (kind) {
                SOUND_MUSIC -> R.raw.abertura
                SOUND_ALARM -> R.raw.alarm
                else -> R.raw.chime
            }
            val afd = resources.openRawResourceFd(recurso)
            val p = MediaPlayer()
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(
                        if (kind == SOUND_MUSIC) AudioAttributes.CONTENT_TYPE_MUSIC
                        else AudioAttributes.CONTENT_TYPE_SONIFICATION
                    )
                    .build()
            )
            p.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            p.isLooping = true
            p.prepare()
            val sessao = Repo.session
            if (kind != SOUND_CHIME && sessao != null) {
                val v = loudVolume(sessao, System.currentTimeMillis())
                p.setVolume(v, v)
            }
            p.start()
            player = p
            currentSound = kind
            Log.i(TAG, "som=" + when (kind) {
                SOUND_MUSIC -> "musica"
                SOUND_ALARM -> "bipe"
                else -> "checagem"
            })
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao tocar o som", e)
        }
        acquireWakeLock()
        if (kind == SOUND_ALARM) startVibration() else stopVibration()
    }

    /** Música da abertura primeiro; passado o prazo, ou numa recaída, o bipe. */
    private fun loudSound(s: Session, now: Long = System.currentTimeMillis()): Int {
        val c = Repo.config
        val naMusica = c.musicFirst && s.phase == Phase.RINGING &&
            now - s.phaseSince < c.musicSeconds.coerceIn(15, 600) * 1000L
        return if (naMusica) SOUND_MUSIC else SOUND_ALARM
    }

    private fun loudVolume(s: Session, now: Long): Float {
        // Começa quase inaudível e sobe em curva exponencial, que é como o ouvido
        // percebe o volume: assim a subida soa constante, sem susto no começo.
        // (Uma reta, como era antes, já começava alta com o aparelho no máximo.)
        val ramp = if (Repo.config.gradualVolume && s.phase == Phase.RINGING) {
            val t = ((now - s.phaseSince) / RAMP_MS.toFloat()).coerceIn(0f, 1f)
            (RAMP_START * Math.pow((1f / RAMP_START).toDouble(), t.toDouble()).toFloat()).coerceIn(RAMP_START, 1f)
        } else {
            1f
        }
        return when {
            // Silêncio curto para o microfone ouvir a afirmação.
            now < Repo.silenceUntil -> 0f
            // Trégua: enquanto a pessoa está resolvendo a missão o som fica baixo.
            now < Repo.quietUntil -> minOf(0.07f, ramp)
            else -> ramp
        }
    }

    /**
     * Até onde o volume do aparelho vai enquanto o alarme toca. O padrão é 75%:
     * no máximo, o tablet acordava a casa inteira. Vale para todos os alarmes.
     */
    private fun teto(): Float = Repo.config.maxVolume.coerceIn(VOLUME_MINIMO, 100) / 100f

    private fun keepLoud(s: Session, now: Long) {
        // Se alguém mexeu no volume do aparelho, ele volta para o teto escolhido.
        setStreamVolume(teto())
        val volume = loudVolume(s, now)
        try {
            player?.setVolume(volume, volume)
        } catch (e: Exception) {
            // o player pode ter sido liberado entre um tique e outro
        }
    }

    private fun setStreamVolume(fraction: Float) {
        try {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val target = (max * fraction).toInt().coerceIn(1, max)
            if (audio.getStreamVolume(AudioManager.STREAM_ALARM) != target) {
                audio.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
            }
        } catch (e: Exception) {
            // "Não perturbe" pode bloquear a troca de volume; o som toca mesmo assim
        }
    }

    private fun releasePlayer() {
        try {
            player?.stop()
        } catch (e: Exception) {
            // já estava parado
        }
        try {
            player?.release()
        } catch (e: Exception) {
            // nada a fazer
        }
        player = null
        currentSound = SOUND_NONE
    }

    private fun stopSound() {
        releasePlayer()
        stopVibration()
        if (savedVolume >= 0) {
            try {
                audio.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
            } catch (e: Exception) {
                // nada a fazer
            }
            savedVolume = -1
        }
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            // nada a fazer
        }
        wakeLock = null
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "levanta:alarme").apply {
                acquire(RING_TIMEOUT_MS + 5 * 60_000L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sem wake lock", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrator(): Vibrator? = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    private fun startVibration() {
        try {
            val v = vibrator() ?: return
            if (!v.hasVibrator()) return
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0L, 600L, 500L), 0))
        } catch (e: Exception) {
            // aparelhos sem motor de vibração (a maioria dos tablets)
        }
    }

    private fun stopVibration() {
        try {
            vibrator()?.cancel()
        } catch (e: Exception) {
            // nada a fazer
        }
    }

    private fun startTicker() {
        if (ticking) return
        ticking = true
        handler.postDelayed(ticker, 500L)
    }

    private fun stopTicker() {
        ticking = false
        handler.removeCallbacks(ticker)
    }

    // ---------- notificação e tela ----------

    private fun goForeground() {
        ensureChannel()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Não foi possível entrar em primeiro plano", e)
        }
    }

    /** Publica a notificação de novo para a tela cheia reaparecer na troca de fase. */
    private fun refreshNotification() {
        stopForegroundCompat()
        goForeground()
    }

    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            // nada a fazer
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val channel = NotificationChannel(CHANNEL, "Alarme tocando", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Mostra a tela do alarme por cima do bloqueio."
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val sessao = Repo.session
        val recheck = sessao?.phase == Phase.RECHECK
        val missao = sessao != null && sessao.type != Tipo.LEVANTAR
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(
                when {
                    missao -> "Hora da missão: " + sessao?.label.orEmpty()
                    recheck -> "Você continua de pé?"
                    else -> "Hora de levantar"
                }
            )
            .setContentText(
                when {
                    missao -> "Toque para parar o som e abrir a missão."
                    recheck -> "Toque para confirmar antes que o alarme volte."
                    else -> "Toque para abrir e cumprir a missão."
                }
            )
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .build()
    }

    private fun launchUi() {
        try {
            // Funciona direto quando o app está visível ou tem a permissão de
            // sobrepor outros apps; nos outros casos quem abre é a notificação.
            startActivity(Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "Abertura direta da tela bloqueada pelo sistema", e)
        }
    }
}
