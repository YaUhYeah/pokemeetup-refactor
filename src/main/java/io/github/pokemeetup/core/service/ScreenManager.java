package io.github.pokemeetup.core.service;

import com.badlogic.gdx.Screen;

public interface ScreenManager {
    void showScreen(Class<? extends Screen> screenClass);
    void goBack();
}
