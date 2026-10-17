package com.parlour.management;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.assertTrue;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

@RunWith(AndroidJUnit4.class)
public class MainActivitySmokeTest {
    @Test
    public void launchesAndNavigatesToOwnerRegistration() {
        Context context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("parlour", Context.MODE_PRIVATE)
                .edit().clear().putString("base", "").commit();

        assertTrue("Debug build should permit local HTTP API connections", (context.getApplicationInfo().flags & ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC) != 0);

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withText("Connect your app")).check(matches(isDisplayed()));
            onView(withId(R.id.account)).perform(click());
            onView(withText("Your space")).check(matches(isDisplayed()));
            onView(withText("Register as an owner")).perform(click());
            onView(withText("Create your owner account")).check(matches(isDisplayed()));
            onView(withText("Create account")).check(matches(isDisplayed()));
        }
    }
}
