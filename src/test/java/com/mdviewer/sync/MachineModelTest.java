package com.mdviewer.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parts of the model lookup that do not depend on which machine the tests run on.
 *
 * <p>The lookup itself asks the operating system and cannot be asserted against a fixed
 * answer - a machine that reports nothing is a perfectly ordinary machine. What can be
 * pinned down is what is done with what comes back, which is where every mistake here
 * would show: a doubled manufacturer, or a placeholder shown to somebody as the name of
 * their laptop.
 */
class MachineModelTest {

    @Test
    void readsTheValueOutOfRegistryOutput() {
        String output = """

                HKEY_LOCAL_MACHINE\\HARDWARE\\DESCRIPTION\\System\\BIOS
                    SystemProductName    REG_SZ    HP ZBook Studio 15.6 inch G8 Mobile Workstation PC

                """;

        // The value has spaces in it, so the split is on the type column, not on whitespace.
        assertEquals("HP ZBook Studio 15.6 inch G8 Mobile Workstation PC",
                MachineModel.value(output));
    }

    @Test
    void answersNothingWhenTheKeyIsNotThere() {
        assertEquals("", MachineModel.value("ERROR: The system was unable to find the "
                + "specified registry key or value."));
    }

    @Test
    void doesNotRepeatAManufacturerTheModelAlreadyNames() {
        // HP's product name begins with HP. "HP HP ZBook Studio" reads as a bug in the one
        // place the reader is looking for a name they recognise.
        assertEquals("HP ZBook Studio 15.6 inch G8",
                MachineModel.withMaker("HP ZBook Studio 15.6 inch G8", "HP"));
    }

    @Test
    void addsAManufacturerTheModelLeavesOut() {
        // Lenovo's does not, and "20XW00E1UK" alone names nothing.
        assertEquals("LENOVO 20XW00E1UK", MachineModel.withMaker("20XW00E1UK", "LENOVO"));
    }

    @Test
    void withoutAModelThereIsNothingToSay() {
        assertEquals("", MachineModel.withMaker("", "Dell Inc."));
    }

    @Test
    void throwsAwayTheThingsManufacturersLeaveInTheField() {
        /* Plenty of boards ship with the prompt still in them. Showing somebody "To be
           filled by O.E.M." as the name of their machine is worse than showing nothing,
           because nothing falls back to the hostname and this does not. */
        assertEquals("", MachineModel.clean("To be filled by O.E.M."));
        assertEquals("", MachineModel.clean("System Product Name"));
        assertEquals("", MachineModel.clean("Default string"));
        assertEquals("HP ZBook Studio", MachineModel.clean("  HP ZBook Studio  "));
    }

    @Test
    void asksTheSystemOnlyOnce() {
        // It runs a process on two of the three platforms, and the label is built on every
        // request the client makes.
        assertSame(MachineModel.of(), MachineModel.of());
        assertTrue(MachineModel.of() != null);
    }
}
