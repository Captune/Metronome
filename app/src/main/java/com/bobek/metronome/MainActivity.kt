/*
 * This file is part of Metronome.
 * Copyright (C) 2022 Philipp Bobek <philipp.bobek@mailbox.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Metronome is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.bobek.metronome

import android.content.res.ColorStateList
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
import android.widget.ImageView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatDelegate.NightMode
import androidx.core.content.ContextCompat.getColor
import androidx.databinding.DataBindingUtil
import com.bobek.metronome.databinding.AboutAlertDialogViewBinding
import com.bobek.metronome.databinding.ActivityMainBinding
import com.bobek.metronome.view.model.MetronomeViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.*

private const val TAG = "MainActivity"
private const val MINUTE_IN_MILLIS = 60_000L
private const val MAX_STREAMS = 8
private const val DEFAULT_SOUND_PRIORITY = 1
private const val NO_LOOP = 0
private const val VOLUME_MAX = 1.0f
private const val NORMAL_PLAYBACK = 1.0f
private const val SOUND_ID_UNINITIALIZED = -1

class MainActivity : AppCompatActivity() {

    private lateinit var optionsMenu: Menu
    private lateinit var binding: ActivityMainBinding
    private lateinit var soundPool: SoundPool

    private val metronomeViewModel = MetronomeViewModel()

    private var timer = Timer()

    private var strongTickSoundId = SOUND_ID_UNINITIALIZED
    private var weakTickSoundId = SOUND_ID_UNINITIALIZED
    private var subTickSoundId = SOUND_ID_UNINITIALIZED

    private var counter = 0L
    private var lastTickTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        binding.lifecycleOwner = this
        binding.metronome = metronomeViewModel

        setSupportActionBar(binding.toolbar)

        soundPool = buildSoundPool()
        strongTickSoundId = soundPool.load(this, R.raw.strong_tick, DEFAULT_SOUND_PRIORITY)
        weakTickSoundId = soundPool.load(this, R.raw.weak_tick, DEFAULT_SOUND_PRIORITY)
        subTickSoundId = soundPool.load(this, R.raw.sub_tick, DEFAULT_SOUND_PRIORITY)

        metronomeViewModel.playing.observe(this) { playing -> if (playing) startMetronome() }
        metronomeViewModel.playing.observe(this) { playing -> if (!playing) stopMetronome() }
        metronomeViewModel.beatsData.observe(this) { dataChanged() }
        metronomeViewModel.subdivisionsData.observe(this) { dataChanged() }
        metronomeViewModel.tempoData.observe(this) { dataChanged() }
    }

    private fun buildSoundPool() = SoundPool.Builder()
        .setMaxStreams(MAX_STREAMS)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                .build()
        )
        .build()

    private fun startMetronome() {
        window.addFlags(FLAG_KEEP_SCREEN_ON)
        resetTimer()
        lastTickTime = System.currentTimeMillis()
        timer.scheduleAtFixedRate(getTickerTask(), Date(lastTickTime), calculateTickerPeriod())
        Log.i(TAG, "Started metronome")
    }

    private fun stopMetronome() {
        window.clearFlags(FLAG_KEEP_SCREEN_ON)
        resetTimer()
        counter = 0L
        Log.i(TAG, "Stopped metronome")
    }

    private fun resetTimer() {
        timer.cancel()
        timer = Timer()
    }

    private fun dataChanged() {
        if (metronomeViewModel.playing.value == true) {
            adjustMetronome()
        }
    }

    private fun adjustMetronome() {
        resetTimer()
        val tickerPeriod = calculateTickerPeriod()
        val startTime = lastTickTime + tickerPeriod
        timer.scheduleAtFixedRate(getTickerTask(), Date(startTime), tickerPeriod)
        Log.i(TAG, "Adjusted metronome")
    }

    private fun getTickerTask() = object : TimerTask() {
        override fun run() {
            lastTickTime = System.currentTimeMillis()
            val currentBeat = getCurrentBeat()
            val currentTickType = getCurrentTickType()
            Log.d(TAG, "Playing tick $counter which is beat $currentBeat and $currentTickType")
            playTick(currentTickType)
            visualizeTick(currentBeat, currentTickType)
            counter++
        }
    }

    private fun getCurrentBeat() = (((counter / getSubdivisions().value) % getBeats().value) + 1).toInt()

    private fun getCurrentTickType(): TickType {
        return when {
            isStrongTick() -> TickType.STRONG
            isWeakTick() -> TickType.WEAK
            else -> TickType.SUB
        }
    }

    private fun playTick(tickType: TickType) {
        when (tickType) {
            TickType.STRONG -> play(strongTickSoundId)
            TickType.WEAK -> play(weakTickSoundId)
            TickType.SUB -> play(subTickSoundId)
        }
    }

    private fun calculateTickerPeriod() = MINUTE_IN_MILLIS / getTempo().value / getSubdivisions().value

    private fun isStrongTick() = counter % (getBeats().value * getSubdivisions().value) == 0L

    private fun isWeakTick() = counter % getSubdivisions().value == 0L

    private fun play(soundId: Int) {
        val streamId = soundPool.play(
            soundId,
            VOLUME_MAX,
            VOLUME_MAX,
            DEFAULT_SOUND_PRIORITY,
            NO_LOOP,
            NORMAL_PLAYBACK
        )
        Log.v(TAG, "Started stream with ID <$streamId>")
    }

    private fun visualizeTick(beat: Int, tickType: TickType) {
        if (tickType == TickType.STRONG) {
            getBeatsVisualizations()[beat]?.also {
                runOnUiThread {
                    visualizeTick(it, R.color.colorSecondaryDark, R.color.colorPrimaryDark)
                }
            }
        } else if (tickType == TickType.WEAK) {
            getBeatsVisualizations()[beat]?.also {
                runOnUiThread {
                    visualizeTick(it, R.color.colorSecondary, R.color.colorPrimary)
                }
            }
        }
    }

    private fun getBeatsVisualizations(): Map<Int, ImageView> {
        return mapOf(
            1 to binding.contentMain.beatsVisualization1Image,
            2 to binding.contentMain.beatsVisualization2Image,
            3 to binding.contentMain.beatsVisualization3Image,
            4 to binding.contentMain.beatsVisualization4Image,
            5 to binding.contentMain.beatsVisualization5Image,
            6 to binding.contentMain.beatsVisualization6Image,
            7 to binding.contentMain.beatsVisualization7Image,
            8 to binding.contentMain.beatsVisualization8Image
        )
    }

    private fun visualizeTick(image: ImageView, @ColorRes flashColorId: Int, @ColorRes originalColorId: Int) {
        image
            .animate()
            .setDuration(200L)
            .withStartAction {
                image.imageTintList = ColorStateList.valueOf(getColor(this, flashColorId))
            }
            .withEndAction {
                image.imageTintList = ColorStateList.valueOf(getColor(this, originalColorId))
            }
            .start()
    }

    private fun getBeats() = metronomeViewModel.beatsData.value!!

    private fun getSubdivisions() = metronomeViewModel.subdivisionsData.value!!

    private fun getTempo() = metronomeViewModel.tempoData.value!!

    override fun onPause() {
        super.onPause()
        metronomeViewModel.playing.value = false
    }

    override fun onDestroy() {
        super.onDestroy()
        soundPool.release()
        strongTickSoundId = SOUND_ID_UNINITIALIZED
        weakTickSoundId = SOUND_ID_UNINITIALIZED
        subTickSoundId = SOUND_ID_UNINITIALIZED
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        optionsMenu = menu
        updateNightModeIcon()
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_night_mode -> {
                toggleNightMode()
                true
            }
            R.id.action_about -> {
                showAboutPopup()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun toggleNightMode() {
        when (AppCompatDelegate.getDefaultNightMode()) {
            AppCompatDelegate.MODE_NIGHT_NO -> changeNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            AppCompatDelegate.MODE_NIGHT_YES -> changeNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            else -> changeNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
    }

    private fun changeNightMode(@NightMode mode: Int) {
        Log.d(TAG, "Setting night mode to value $mode")
        AppCompatDelegate.setDefaultNightMode(mode)
        updateNightModeIcon()
    }

    private fun showAboutPopup() {
        val alertDialogBuilder = MaterialAlertDialogBuilder(this)
        val dialogContextInflater = LayoutInflater.from(alertDialogBuilder.context)

        val dialogBinding = AboutAlertDialogViewBinding.inflate(dialogContextInflater, null, false)
        dialogBinding.version = BuildConfig.VERSION_NAME
        dialogBinding.copyrightText.movementMethod = LinkMovementMethod.getInstance()
        dialogBinding.licenseText.movementMethod = LinkMovementMethod.getInstance()
        dialogBinding.sourceCodeText.movementMethod = LinkMovementMethod.getInstance()

        alertDialogBuilder
            .setTitle(R.string.app_name)
            .setView(dialogBinding.root)
            .setNeutralButton(R.string.ok) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun updateNightModeIcon() {
        optionsMenu
            .findItem(R.id.action_night_mode)
            .setIcon(getNightModeIcon())
    }

    @DrawableRes
    private fun getNightModeIcon(): Int {
        return when (AppCompatDelegate.getDefaultNightMode()) {
            AppCompatDelegate.MODE_NIGHT_NO -> R.drawable.ic_night_mode_no
            AppCompatDelegate.MODE_NIGHT_YES -> R.drawable.ic_night_mode_yes
            else -> R.drawable.ic_night_mode_auto
        }
    }
}
