package eu.ha3.presencefootsteps.sound.acoustics;

import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonObject;

import eu.ha3.presencefootsteps.sound.Options;
import eu.ha3.presencefootsteps.sound.State;
import eu.ha3.presencefootsteps.sound.player.SoundPlayer;
import net.minecraft.entity.Entity;

class EventSelectorAcoustics implements NamedAcoustic {
    private final String name;

    private final Map<State, Acoustic> pairs = new HashMap<>();

    public EventSelectorAcoustics(String name, JsonObject json, AcousticsJsonParser context) {
        this.name = name;

        for (State i : State.values()) {
            String eventName = i.getName();

            if (json.has(eventName)) {
                pairs.put(i, context.solveAcoustic(json.get(eventName)));
            }
        }
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void playSound(SoundPlayer player, Entity location, State event, Options inputOptions) {
        if (pairs.containsKey(event)) {
            pairs.get(event).playSound(player, location, event, inputOptions);
        } else if (event.canTransition()) {
            playSound(player, location, event.getTransitionDestination(), inputOptions);
            // the possibility of a resonance cascade scenario is extremely unlikely
        }
    }
}
