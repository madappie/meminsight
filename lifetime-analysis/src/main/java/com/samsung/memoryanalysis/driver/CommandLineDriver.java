/*
 * Copyright (c) 2014 Samsung Electronics Co., Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.samsung.memoryanalysis.driver;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.Map;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;

import com.google.gson.Gson;
import com.ibm.wala.util.collections.HashMapFactory;
import com.samsung.memoryanalysis.allocstats.AllocationSiteStats;
import com.samsung.memoryanalysis.allocstats.AllocationSiteStats.AllocSiteResult;
import com.samsung.memoryanalysis.context.ContextProvider;
import com.samsung.memoryanalysis.options.MemoryAnalysisOptions;
import com.samsung.memoryanalysis.referencecounter.DummyUnreachabilityAnalysis;
import com.samsung.memoryanalysis.referencecounter.ReferenceCounter;
import com.samsung.memoryanalysis.referencecounter.heap.JGraphHeap;
import com.samsung.memoryanalysis.staleness.StreamingStalenessAnalysis;
import com.samsung.memoryanalysis.traceparser.EnhancedTraceAnalysisRunner;
import com.samsung.memoryanalysis.traceparser.ProgressMonitor;
import com.samsung.memoryanalysis.traceparser.TraceAnalysisRunner;
import com.samsung.memoryanalysis.traceparser.TracePrettyPrinter;

/**
 * Created by s.jensen on 6/12/14.
 */
public class CommandLineDriver {

    private static int lastPercent = -1;

    public static void printProgBar(int total, int prog) {
        final int percent = (int) (((float) prog) / ((float) total) * 100);
        if (percent > lastPercent) {
            lastPercent = percent;
        } else
            return;

        final StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < 50; i++) {
            if (i < (percent / 2)) {
                bar.append("=");
            } else if (i == (percent / 2)) {
                bar.append(">");
            } else {
                bar.append(" ");
            }
        }
        bar.append(String.format("]    %d%%   (%d/%d)    ", percent, prog, total));
        System.out.print("\r" + bar.toString());
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        final OptionParser parser = new OptionParser();
        parser.accepts("context", "Run only the context analysis");
        parser.accepts("ref", "Run the reference count analysis only");
        parser.accepts("staleness", "Run the staleness analysis.  Output is written in three files: staleness-trace, lastuse-trace, and unreachable-trace");
        parser.accepts("pretty-print", "Just parse and pretty print the trace");
        parser.accepts("no-progress", "Don't print progress bar");
        parser.accepts("access-paths",
                "Reads object ids on stdin and timestamp on stdin, prints access paths on stdout");
        parser.accepts("nodejs", "Model the module scope of nodejs");
        parser.accepts("site-stats", "Run the analysis that computes statistics for allocation sites");
        OptionSpec<String> traceOpt = parser.accepts("trace", "Trace file to analyze").withRequiredArg()
                .describedAs("trace file").ofType(String.class);
        OptionSpec<String> dirOpt = parser.accepts("directory", "Directory containing the instrumented source code")
                .withRequiredArg().describedAs("directory").ofType(String.class);
        final OptionSet options = parser.parse(args);
        InputStream traceStream;
        File dir = null;
        if (options.has(traceOpt)) {
            String file = options.valueOf(traceOpt);
            traceStream = new BufferedInputStream(new FileInputStream(file));
            dir = new File(file).getParentFile();
        } else {
            traceStream = System.in;
            if (options.has(dirOpt))
                dir = new File(options.valueOf(dirOpt));
            else {
                System.out.println("In streaming mode you must provide a directory (with --directory)");
                System.exit(1);
            }

        }

        ProgressMonitor prog = new ProgressMonitor() {
            int total;

            @Override
            public void start(int total) {
                this.total = total;
            }

            @Override
            public void tick(int progress) {
                if (!options.has("no-progress"))
                    printProgBar(total, progress);
            }
        };
        MemoryAnalysisOptions refOptions = new MemoryAnalysisOptions();
        if (options.has("nodejs")) {
            refOptions.setModuleScope();
        }
        if (options.has("ref")) {
            ReferenceCounter<Void> f = new ReferenceCounter<Void>(new JGraphHeap(), null, refOptions);
            new TraceAnalysisRunner(traceStream, prog, dir).runAnalysis(new ContextProvider<Void>(f, refOptions));
            // System.out.println(res);
        } else if (options.has("context")) {
            new TraceAnalysisRunner(traceStream, prog, dir).runAnalysis(new ContextProvider<Void>(null, refOptions));
        } else if (options.has("staleness")) {
            OutputStream out = null, lastUseOut = null, unreachOut = null, iidOut = null;
            try {
                out = new BufferedOutputStream(new FileOutputStream(new File(
                        dir, "staleness-trace")));
                lastUseOut = new BufferedOutputStream(new FileOutputStream(
                        new File(dir, "lastuse-trace")));
                unreachOut = new BufferedOutputStream(new FileOutputStream(
                        new File(dir, "unreachable-trace")));
                iidOut = new BufferedOutputStream(new FileOutputStream(
                        new File(dir, "updiid-trace")));
                ReferenceCounter<Void> f = new ReferenceCounter<Void>(
                        new JGraphHeap(), new StreamingStalenessAnalysis(out,
                                lastUseOut, unreachOut, iidOut), refOptions);
                new TraceAnalysisRunner(traceStream, prog, dir)
                        .runAnalysis(new ContextProvider<Void>(f, refOptions));
            } finally {
                if (out != null) out.close();
                if (lastUseOut != null) lastUseOut.close();
                if (unreachOut != null) unreachOut.close();
                if (iidOut != null) iidOut.close();
            }
        } else if (options.has("site-stats")) {
            InputStream lastUseIn = null, unreachIn = null, iidIn = null;
            PrintWriter memJSOut = null;
            try {
                lastUseIn = new BufferedInputStream(new FileInputStream(new File(dir, "lastuse-trace")));
                unreachIn = new BufferedInputStream(new FileInputStream(new File(dir, "unreachable-trace")));
                iidIn = new BufferedInputStream(new FileInputStream(new File(dir, "updiid-trace")));
                AllocationSiteStats allocStats = new AllocationSiteStats();
                Map<String, AllocSiteResult> results = new EnhancedTraceAnalysisRunner(traceStream, lastUseIn, unreachIn, iidIn, prog, dir).runAnalysis(allocStats);
                memJSOut = new PrintWriter(new File(dir, "siteStats.json"));
                Gson gson = new Gson();
                String json = gson.toJson(results);
                memJSOut.println(json);
            } finally {
                if (lastUseIn != null) lastUseIn.close();
                if (unreachIn != null) unreachIn.close();
                if (iidIn != null) iidIn.close();
                if (memJSOut != null) memJSOut.close();
            }
        } else if (options.has("pretty-print")) {
            new TraceAnalysisRunner(traceStream, prog, dir).runAnalysis(new TracePrettyPrinter());
        } else if (options.has("access-paths")) {
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
            final Map<Integer, Integer> watchList = HashMapFactory.make();
            String line;
            while ((line = in.readLine()) != null) {
                String[] comp = line.split(",");
                assert comp.length == 2;
                int objectId = Integer.parseInt(comp[0]);
                int printAtTime = Integer.parseInt(comp[1]);
                watchList.put(objectId, printAtTime);
            }
            refOptions.setAccessPathObjects(watchList);
            ReferenceCounter<Void> f = new ReferenceCounter<Void>(new JGraphHeap(),
                    new DummyUnreachabilityAnalysis<Void>(), refOptions);
            new TraceAnalysisRunner(traceStream, prog, dir).runAnalysis(new ContextProvider<Void>(f, refOptions));
        }
    }
}
