package com.melisma.app.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import com.melisma.app.R

/**
 * "Watch the road."
 *
 * Shown at the start of every drive rather than once ever, and that is deliberate: this is not a
 * licence agreement to be clicked past, it is the reminder that the thing you just opened is words
 * on a screen while you are driving. A notice that appears once and is never seen again is a notice
 * nobody reads, and the cost of showing it each time you plug the phone in is one tap.
 *
 * It says the true thing rather than a reassuring one: the words move, and moving words are as easy
 * to stare at as they are to glance at. What the screen does *not* do is ask for anything — there is
 * nothing to scroll, nothing to answer, and the controls are the car's own.
 */
class RoadWarningScreen(
    carContext: CarContext,
    private val surface: CarSurfaceState,
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val message = carContext.getString(R.string.car_warning_body)

        return MessageTemplate.Builder(message)
            .setTitle(carContext.getString(R.string.car_warning_title))
            .setIcon(
                CarIcon.Builder(
                    IconCompat.createWithResource(carContext, R.drawable.ic_car_note),
                ).build(),
            )
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.car_warning_accept))
                    .setOnClickListener {
                        carContext.getCarService(ScreenManager::class.java)
                            .push(CarLyricsScreen(carContext, surface))
                    }
                    .build(),
            )
            .build()
    }
}
