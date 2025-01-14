// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.node.admin.task.util.systemd;

import com.yahoo.vespa.hosted.node.admin.component.TaskContext;
import com.yahoo.vespa.hosted.node.admin.task.util.process.CommandResult;
import com.yahoo.vespa.hosted.node.admin.task.util.process.Terminal;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Control the systemd system and service manager
 *
 * @author hakonhall
 */
public class SystemCtl {

    // Valid systemd property names from looking at a couple of services.
    private static final Pattern PROPERTY_NAME_PATTERN = Pattern.compile("^[a-zA-Z]+$");

    // Last line of `systemctl list-unit-files <unit>` prints '0 unit files listed.'
    private static final Pattern UNIT_FILES_LISTED_PATTERN = Pattern.compile("([0-9]+) unit files listed\\.");

    private static final Pattern ACTIVE_STATE_PROPERTY_PATTERN = createPropertyPattern("ActiveState");

    private final Terminal terminal;

    private static Pattern createPropertyPattern(String propertyName) {
        if (!PROPERTY_NAME_PATTERN.matcher(propertyName).matches()) {
            throw new IllegalArgumentException("Property name does not match " + PROPERTY_NAME_PATTERN);
        }

        // Make ^ and $ match beginning and end of lines.
        String regex = String.format("(?md)^%s=(.*)$", propertyName);

        return Pattern.compile(regex);
    }

    public SystemCtl(Terminal terminal) {
        this.terminal = terminal;
    }

    public void daemonReload(TaskContext taskContext) {
        terminal.newCommandLine(taskContext)
                .add("systemctl", "daemon-reload")
                .execute();
    }

    public SystemCtlEnable enable(String unit) { return new SystemCtlEnable(unit); }
    public SystemCtlDisable disable(String unit) { return new SystemCtlDisable(unit); }
    public SystemCtlStart start(String unit) { return new SystemCtlStart(unit); }
    public SystemCtlStop stop(String unit) { return new SystemCtlStop(unit); }
    public SystemCtlRestart restart(String unit) { return new SystemCtlRestart(unit); }

    public boolean serviceExists(TaskContext context, String unit) {
        return terminal.newCommandLine(context)
                .add("systemctl", "list-unit-files", unit + ".service").executeSilently()
                .mapOutput(output -> {
                    // Last line of the form: "1 unit files listed."
                    Matcher matcher = UNIT_FILES_LISTED_PATTERN.matcher(output);
                    if (!matcher.find()) {
                        throw new IllegalArgumentException();
                    }

                    return !matcher.group(1).equals("0");
                });
    }

    /** Returns true if the unit exists and is active (i.e. running). unit is e.g. "docker". */
    public boolean isActive(TaskContext context, String unit) {
        return terminal.newCommandLine(context)
                .add("systemctl", "--quiet", "is-active", unit + ".service")
                .ignoreExitCode()
                .executeSilently()
                .map(CommandResult::getExitCode) == 0;
    }

    public class SystemCtlEnable extends SystemCtlCommand {
        private SystemCtlEnable(String unit) {
            super("enable", unit);
        }

        protected boolean isAlreadyConverged(TaskContext context) {
            return isUnitEnabled(context);
        }
    }

    public class SystemCtlDisable extends SystemCtlCommand {
        private SystemCtlDisable(String unit) {
            super("disable", unit);
        }

        protected boolean isAlreadyConverged(TaskContext context) {
            return !isUnitEnabled(context);
        }
    }

    public class SystemCtlStart extends SystemCtlCommand {
        private SystemCtlStart(String unit) {
            super("start", unit);
        }

        protected boolean isAlreadyConverged(TaskContext context) {
            String activeState = getSystemCtlProperty(context, ACTIVE_STATE_PROPERTY_PATTERN);
            return Objects.equals(activeState, "active");
        }
    }

    public class SystemCtlStop extends SystemCtlCommand {
        private SystemCtlStop(String unit) {
            super("stop", unit);
        }

        protected boolean isAlreadyConverged(TaskContext context) {
            String activeState = getSystemCtlProperty(context, ACTIVE_STATE_PROPERTY_PATTERN);
            return Objects.equals(activeState, "inactive");
        }
    }

    public class SystemCtlRestart extends SystemCtlCommand {
        private SystemCtlRestart(String unit) {
            super("restart", unit);
        }

        protected boolean isAlreadyConverged(TaskContext context) {
            return false;
        }
    }

    public abstract class SystemCtlCommand {

        private final String command;
        private final String unit;

        private SystemCtlCommand(String command, String unit) {
            this.command = command;
            this.unit = unit;
        }

        protected abstract boolean isAlreadyConverged(TaskContext context);

        public boolean converge(TaskContext context) {
            if (isAlreadyConverged(context)) {
                return false;
            }

            terminal.newCommandLine(context)
                    .add("systemctl", command, unit)
                    .execute();

            return true;
        }

        /** Returns true if unit is enabled */
        boolean isUnitEnabled(TaskContext context) {
            return terminal.newCommandLine(context)
                           .add("systemctl", "--quiet", "is-enabled", unit)
                           .ignoreExitCode()
                           .executeSilently()
                           .map(CommandResult::getExitCode) == 0;
        }

        /**
         * @param propertyPattern Pattern to match the output of systemctl show command with
         *                        exactly 1 group. The matchng group must exist.
         * @return The matched group from the 'systemctl show' output.
         */
        String getSystemCtlProperty(TaskContext context, Pattern propertyPattern) {
            return terminal.newCommandLine(context)
                    .add("systemctl", "show", unit)
                    .executeSilently()
                    .mapOutput(output -> extractProperty(output, propertyPattern));
        }
    }


    /**
     * Find the systemd property value of the property (given by propertyPattern)
     * matching the 'systemctl show' output (given by showProcess).
     */
    private static String extractProperty(String showOutput, Pattern propertyPattern) {
        Matcher matcher = propertyPattern.matcher(showOutput);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Pattern '" + propertyPattern +
                    "' didn't match output");
        } else if (matcher.groupCount() != 1) {
            throw new IllegalArgumentException("Property pattern must have exactly 1 group");
        }

        return matcher.group(1);
    }

}
