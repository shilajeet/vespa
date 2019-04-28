// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.feed.perf;

import com.yahoo.messagebus.routing.Route;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;

/**
 * @author Simon Thoresen Hult
 */
class FeederParams {

    enum DumpFormat {JSON, VESPA};
    private InputStream stdIn = System.in;
    private PrintStream stdErr = System.err;
    private PrintStream stdOut = System.out;
    private Route route = Route.parse("default");
    private String configId = "client";
    private OutputStream dumpStream = null;
    private DumpFormat dumpFormat = DumpFormat.JSON;
    private boolean benchmarkMode = false;
    private int numDispatchThreads = 1;
    private int maxPending = 0;

    InputStream getStdIn() {
        return stdIn;
    }

    FeederParams setStdIn(InputStream stdIn) {
        this.stdIn = stdIn;
        return this;
    }

    PrintStream getStdErr() {
        return stdErr;
    }

    FeederParams setStdErr(PrintStream stdErr) {
        this.stdErr = stdErr;
        return this;
    }

    PrintStream getStdOut() {
        return stdOut;
    }

    FeederParams setStdOut(PrintStream stdOut) {
        this.stdOut = stdOut;
        return this;
    }

    Route getRoute() {
        return route;
    }
    OutputStream getDumpStream() { return dumpStream; }
    FeederParams setDumpStream(OutputStream dumpStream) {
        this.dumpStream = dumpStream;
        return this;
    }

    DumpFormat getDumpFormat() { return dumpFormat; }
    FeederParams setDumpFormat(DumpFormat dumpFormat) {
        this.dumpFormat = dumpFormat;
        return this;
    }

    String getConfigId() {
        return configId;
    }

    FeederParams setConfigId(String configId) {
        this.configId = configId;
        return this;
    }

    FeederParams setMaxPending(int maxPending) {
        this.maxPending = maxPending;
        return this;
    }

    boolean isSerialTransferEnabled() {
        return maxPending == 1;
    }

    FeederParams setSerialTransfer() {
        maxPending = 1;
        numDispatchThreads = 1;
        return this;
    }

    int getNumDispatchThreads() { return numDispatchThreads; }
    int getMaxPending() { return maxPending; }
    boolean isBenchmarkMode() { return benchmarkMode; }

    FeederParams parseArgs(String... args) throws ParseException, FileNotFoundException {
        Options opts = new Options();
        opts.addOption("s", "serial", false, "use serial transfer mode, at most 1 pending operation and a single thread");
        opts.addOption("n", "numthreads", true, "Number of clients for sending messages. Anything, but 1 will bypass sequencing by document id.");
        opts.addOption("m", "maxpending", true, "Max number of inflights messages. Default is auto.");
        opts.addOption("r", "route", true, "Route for sending messages. default is 'default'....");
        opts.addOption("b", "mode", true, "Mode for benchmarking.");
        opts.addOption("o", "output", true, "File to write to. Extensions gives format (.xml, .json, .vespa) json will be produced if no extension.");

        CommandLine cmd = new DefaultParser().parse(opts, args);

        if (cmd.hasOption('n')) {
            numDispatchThreads = Integer.valueOf(cmd.getOptionValue('n').trim());
        }
        if (cmd.hasOption('m')) {
            maxPending = Integer.valueOf(cmd.getOptionValue('m').trim());
        }
        if (cmd.hasOption('r')) {
            route = Route.parse(cmd.getOptionValue('r').trim());
        }
        benchmarkMode =  cmd.hasOption('b');
        if (cmd.hasOption('o')) {
            String fileName = cmd.getOptionValue('o').trim();
            dumpStream = new FileOutputStream(new File(fileName));
            if (fileName.endsWith(".vespa")) {
                dumpFormat = DumpFormat.VESPA;
            }
        }
        if (cmd.hasOption('s')) {
            setSerialTransfer();
        }

        return this;
    }

}
