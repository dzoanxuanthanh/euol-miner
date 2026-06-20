package lub;

import java.io.*;
import java.util.*;
import java.lang.management.*;

/**
 * B0 = HUOMIL baseline, based on HUOMIL_Original.
 * This version keeps the paper-style structure:
 *   - Quadruple Entry <N.Item, uo, ruo, N.Idx>
 *   - separate baseList for prefix utility occupancy
 *   - Object[] tailItems during expansion
 *   - exact UBUO by sorting all (uo + ruo) values
 *
 * Only benchmark/counter code is added.
 * Args: <dbFile> <profitFile> <gammaPercent> <runs> <delta1,delta2,...>
 */
public class HUOMIL {
    static long nodeCount = 0;
    static long patternCount = 0;
    static long candidateCount = 0;
    static long generatedEntryCount = 0;
    static long topKCallCount = 0;

    static class Entry {
        Integer nItem;
        float uo;
        float ruo;
        Integer nIdx;

        Entry(Integer nItem, float uo, float ruo, Integer nIdx) {
            this.nItem = nItem;
            this.uo = uo;
            this.ruo = ruo;
            this.nIdx = nIdx;
        }
    }

    static class UtilityList {
        float sumUO = 0f;
        float sumRUO = 0f;
        List<Entry> entries = new ArrayList<>();
        List<Float> baseList = new ArrayList<>();
    }

    static long usedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    static void forceGC() throws Exception {
        Runtime.getRuntime().gc();
        Thread.sleep(200);
        Runtime.getRuntime().gc();
        Thread.sleep(200);
    }

    static void resetPeakUsage() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            try { pool.resetPeakUsage(); } catch (Throwable ignored) {}
        }
    }

    static long peakHeapUsed() {
        long total = 0L;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            try {
                MemoryUsage usage = pool.getPeakUsage();
                if (usage != null && pool.getType() == MemoryType.HEAP) total += usage.getUsed();
            } catch (Throwable ignored) {}
        }
        return total > 0L ? total : usedMemory();
    }

    static double mb(long bytes) {
        return bytes / (1024.0 * 1024.0);
    }

    static void resetCounters() {
        nodeCount = 0;
        patternCount = 0;
        candidateCount = 0;
        generatedEntryCount = 0;
        topKCallCount = 0;
    }

    static float[] parseDeltas(String s) {
        String[] parts = s.split(",");
        float[] deltas = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            deltas[i] = Float.parseFloat(parts[i].trim());
        }
        return deltas;
    }

    // Exact UBUO in the original/simple way: create values and sort descending.
    static float calcUbuo(List<Entry> entries, int minSupCount) {
        topKCallCount++;
        List<Float> values = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            values.add(e.uo + e.ruo);
        }
        values.sort(Collections.reverseOrder());

        float sum = 0f;
        int limit = Math.min(minSupCount, values.size());
        for (int i = 0; i < limit; i++) {
            sum += values.get(i);
        }
        return limit > 0 ? sum / minSupCount : 0f;
    }

    static void mineHuomil(List<Integer> prefix, Map<Integer, UtilityList> CUO_K,
                           float delta, int minSupCount) {
        candidateCount += CUO_K.size();

        for (Map.Entry<Integer, UtilityList> mapEntry : CUO_K.entrySet()) {
            Integer pItem = mapEntry.getKey();
            UtilityList cuoP = mapEntry.getValue();

            int supP = cuoP.entries.size();
            if (supP < minSupCount) continue;

            float avgUo = cuoP.sumUO / supP;
            if (avgUo >= delta) {
                patternCount++;
            }

            float lubuo = (cuoP.sumUO + cuoP.sumRUO) / minSupCount;
            if (lubuo < delta) continue;

            float ubuo = calcUbuo(cuoP.entries, minSupCount);
            if (ubuo < delta) continue;

            nodeCount++;

            Map<Integer, UtilityList> CUO_K_PLUS_1 = new LinkedHashMap<>();

            for (int eIndex = 0; eIndex < cuoP.entries.size(); eIndex++) {
                Entry e = cuoP.entries.get(eIndex);
                float peUo = cuoP.baseList.get(eIndex);

                Integer currItem = e.nItem;
                Integer currIdx = e.nIdx;

                List<Object[]> tailItems = new ArrayList<>();
                while (currItem != null) {
                    Entry ce = CUO_K.get(currItem).entries.get(currIdx);
                    tailItems.add(new Object[]{currItem, ce});
                    currItem = ce.nItem;
                    currIdx = ce.nIdx;
                }

                Integer nextItemNew = null;
                Integer nextIdxNew = null;

                for (int i = tailItems.size() - 1; i >= 0; i--) {
                    Integer extItem = (Integer) tailItems.get(i)[0];
                    Entry ce = (Entry) tailItems.get(i)[1];

                    CUO_K_PLUS_1.putIfAbsent(extItem, new UtilityList());
                    UtilityList targetList = CUO_K_PLUS_1.get(extItem);

                    float newUo = e.uo + ce.uo - peUo;
                    float newRuo = ce.ruo;

                    Entry newEntry = new Entry(nextItemNew, newUo, newRuo, nextIdxNew);
                    int idxInserted = targetList.entries.size();

                    targetList.entries.add(newEntry);
                    targetList.baseList.add(e.uo);
                    targetList.sumUO += newUo;
                    targetList.sumRUO += newRuo;
                    generatedEntryCount++;

                    nextItemNew = extItem;
                    nextIdxNew = idxInserted;
                }
            }

            if (!CUO_K_PLUS_1.isEmpty()) {
                List<Integer> newPrefix = new ArrayList<>(prefix);
                newPrefix.add(pItem);
                mineHuomil(newPrefix, CUO_K_PLUS_1, delta, minSupCount);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        String dbFile = "java/retail.txt";
        String profitFile = "java/retail_profit.txt";
        float gammaRaw = 0.01f;
        int numRuns = 3;
        float[] deltas = new float[]{0.3f};

        if (args.length >= 1) dbFile = args[0];
        if (args.length >= 2) profitFile = args[1];
        if (args.length >= 3) gammaRaw = Float.parseFloat(args[2]);
        if (args.length >= 4) numRuns = Integer.parseInt(args[3]);
        if (args.length >= 5) deltas = parseDeltas(args[4]);

        for (float delta : deltas) {
            System.out.println("\n=== B0 - HUOMIL baseline / original-style ===");
            System.out.println("DB: " + dbFile + " | Profit: " + profitFile);
            System.out.println("minSup Ratio: " + gammaRaw + "% | delta: " + delta + " | runs: " + numRuns);

            forceGC();
            resetPeakUsage();
            long buildStart = System.nanoTime();

            Map<Integer, Integer> profitDict = new HashMap<>();
            try (BufferedReader br = new BufferedReader(new FileReader(profitFile))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    String[] parts = line.split("[,\\s]+");
                    profitDict.put(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
                }
            }

            List<List<int[]>> transactions = new ArrayList<>();
            Map<Integer, Integer> itemSupport = new HashMap<>();
            List<Float> TUs = new ArrayList<>();

            try (BufferedReader br = new BufferedReader(new FileReader(dbFile))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    String[] pairs = line.split("\\s+");
                    List<int[]> trans = new ArrayList<>();
                    float TU = 0f;
                    for (String pair : pairs) {
                        String[] parts = pair.split(",");
                        int item = Integer.parseInt(parts[0]);
                        int qty = Integer.parseInt(parts[1]);
                        trans.add(new int[]{item, qty});
                        Integer profit = profitDict.get(item);
                        if (profit != null) TU += profit * qty;
                    }
                    TUs.add(TU);
                    for (int[] t : trans) {
                        itemSupport.put(t[0], itemSupport.getOrDefault(t[0], 0) + 1);
                    }
                    transactions.add(trans);
                }
            }

            float gamma = gammaRaw / 100f;
            int lenD = transactions.size();
            int minSupCount = (int) Math.ceil(gamma * lenD);

            List<Integer> validItems = new ArrayList<>();
            for (Map.Entry<Integer, Integer> e : itemSupport.entrySet()) {
                if (e.getValue() >= minSupCount) validItems.add(e.getKey());
            }
            validItems.sort((a, b) -> {
                int cmp = Integer.compare(itemSupport.get(a), itemSupport.get(b));
                if (cmp == 0) return Integer.compare(a, b);
                return cmp;
            });

            Map<Integer, UtilityList> GUO_IL = new LinkedHashMap<>();
            for (int item : validItems) GUO_IL.put(item, new UtilityList());

            for (int tid = 0; tid < transactions.size(); tid++) {
                float TU = TUs.get(tid);
                if (TU == 0f) TU = 1f;

                List<int[]> revised = new ArrayList<>();
                for (int[] t : transactions.get(tid)) {
                    if (validItems.contains(t[0])) revised.add(t);
                }
                revised.sort(Comparator.comparingInt(a -> validItems.indexOf(a[0])));

                float ruo = 0f;
                Integer nextItem = null;
                Integer nextIdx = null;

                for (int i = revised.size() - 1; i >= 0; i--) {
                    int item = revised.get(i)[0];
                    int qty = revised.get(i)[1];
                    float uo = (profitDict.get(item) * qty) / TU;

                    Entry entry = new Entry(nextItem, uo, ruo, nextIdx);
                    UtilityList ul = GUO_IL.get(item);
                    int idxInserted = ul.entries.size();
                    ul.entries.add(entry);
                    ul.baseList.add(0f);
                    ul.sumUO += uo;
                    ul.sumRUO += ruo;

                    ruo += uo;
                    nextItem = item;
                    nextIdx = idxInserted;
                }
            }

            long buildEnd = System.nanoTime();
            long buildPeak = peakHeapUsed();
            double buildTimeSec = (buildEnd - buildStart) / 1e9;
            double buildPeakMB = mb(buildPeak);

            double totalTime = 0.0;
            double totalPeak = 0.0;
            double totalExtraPeak = 0.0;

            long finalPatternCount = 0;
            long finalNodeCount = 0;
            long finalCandidateCount = 0;
            long finalGeneratedEntries = 0;
            long finalTopKCalls = 0;

            for (int run = 1; run <= numRuns; run++) {
                resetCounters();
                forceGC();
                long baseline = usedMemory();
                resetPeakUsage();

                long start = System.nanoTime();
                mineHuomil(new ArrayList<>(), GUO_IL, delta, minSupCount);
                long end = System.nanoTime();

                long peak = peakHeapUsed();
                double timeSec = (end - start) / 1e9;
                double peakMB = mb(peak);
                double extraPeakMB = mb(Math.max(0L, peak - baseline));

                totalTime += timeSec;
                totalPeak += peakMB;
                totalExtraPeak += extraPeakMB;

                finalPatternCount = patternCount;
                finalNodeCount = nodeCount;
                finalCandidateCount = candidateCount;
                finalGeneratedEntries = generatedEntryCount;
                finalTopKCalls = topKCallCount;

                System.out.printf(" - Run %d mining-only: %.3f s | PeakTotal %.2f MB | ExtraPeak %.2f MB\n",
                        run, timeSec, peakMB, extraPeakMB);
            }

            double avgTime = totalTime / numRuns;
            double avgPeak = totalPeak / numRuns;
            double avgExtraPeak = totalExtraPeak / numRuns;

            System.out.println("(" + minSupCount + ") - minSup: " + gammaRaw + "% - delta: " + delta);
            System.out.println("TỔNG MẪU: " + finalPatternCount);
            System.out.println("Nodes: " + finalNodeCount);
            System.out.println("Candidate Count: " + finalCandidateCount);
            System.out.println("Generated Entries: " + finalGeneratedEntries);
            System.out.println("UBUO top-k Calls: " + finalTopKCalls);
            System.out.printf("Build Time: %.3f s | Build Peak Heap: %.2f MB\n", buildTimeSec, buildPeakMB);
            System.out.printf("Mining Time Avg: %.3f s | PeakTotal Avg: %.2f MB | ExtraPeak Avg: %.2f MB\n",
                    avgTime, avgPeak, avgExtraPeak);
            System.out.printf("Full Time approx. (Build + Mining Avg): %.3f s | Full Peak approx.: %.2f MB\n",
                    buildTimeSec + avgTime, Math.max(buildPeakMB, avgPeak));
        }
    }
}
