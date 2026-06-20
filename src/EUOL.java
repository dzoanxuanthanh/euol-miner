package lub;

import java.io.*;
import java.util.*;
import java.lang.management.*;

/**
 * B13 = EUOL-Miner-E2: dual co-occurrence filtering
 * Based on B7, adding pair-support matrix PS(x,y).
 * PS is safe at all depths: for Z = X U {y}, with x = last(X), sup(Z) <= PS(x,y).
 * EUCP remains applied only when constructing 2-element extensions.
 *
 * Args: <dbFile> <profitFile> <gammaPercent> <runs> <delta1,delta2,...> [mode]
 * mode: dual(default)=PS all levels + level-1 EUCP; ps=PS only; eucp=B7; off=no PS/EUCP
 */
public class EUOL {
    static final String VERSION = "B13F-dual-filter-candidate-level-2026-05-18-v2";

    static long patternCount = 0;
    static long nodeCount = 0;
    static long candidateCount = 0;
    static long generatedEntryCount = 0;
    static long topKCallCount = 0;
    static long topKEarlyPassCount = 0;
    static long eucpPrunedCount = 0;
    static long pairSupportChecks = 0;
    static long pairSupportPrunedCount = 0;

    static float[][] EUCP;
    static int[][] PAIR_SUPPORT;
    static boolean USE_PAIR_SUPPORT = true;
    static boolean USE_EUCP = true;

    static int GLOBAL_N_ITEMS = 0;
    static int filterRound = 1;
    static int[] filterStamp = new int[0];
    static byte[] filterDecision = new byte[0]; // -1 prune, 1 pass for current pItem/level

    static int[] scratchItems = new int[1024];
    static int[] scratchIdxs = new int[1024];
    static float[] heap = new float[16];

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

    static double mb(long bytes) { return bytes / (1024.0 * 1024.0); }

    static void ensureScratch(int needed) {
        if (needed <= scratchItems.length) return;
        int newCap = scratchItems.length;
        while (newCap < needed) newCap *= 2;
        scratchItems = Arrays.copyOf(scratchItems, newCap);
        scratchIdxs = Arrays.copyOf(scratchIdxs, newCap);
    }

    static class UtilityList {
        int size = 0;
        int[] nItem = new int[16];
        int[] nIdx = new int[16];
        float[] uo = new float[16];
        float[] ruo = new float[16];
        float[] pUo = new float[16];
        float sumUO = 0f;
        float sumRUO = 0f;

        void ensureCapacity(int needed) {
            if (needed <= uo.length) return;
            int newCap = uo.length;
            while (newCap < needed) newCap *= 2;
            nItem = Arrays.copyOf(nItem, newCap);
            nIdx = Arrays.copyOf(nIdx, newCap);
            uo = Arrays.copyOf(uo, newCap);
            ruo = Arrays.copyOf(ruo, newCap);
            pUo = Arrays.copyOf(pUo, newCap);
        }

        int add(int nextItem, float newUo, float newRuo, int nextIdx, float newPUo) {
            ensureCapacity(size + 1);
            int idx = size++;
            nItem[idx] = nextItem;
            nIdx[idx] = nextIdx;
            uo[idx] = newUo;
            ruo[idx] = newRuo;
            pUo[idx] = newPUo;
            sumUO += newUo;
            sumRUO += newRuo;
            return idx;
        }
    }

    static void resetCounters() {
        patternCount = 0;
        nodeCount = 0;
        candidateCount = 0;
        generatedEntryCount = 0;
        topKCallCount = 0;
        topKEarlyPassCount = 0;
        eucpPrunedCount = 0;
        pairSupportChecks = 0;
        pairSupportPrunedCount = 0;
    }

    static void ensureHeap(int k) {
        if (heap.length >= k) return;
        int newCap = heap.length;
        while (newCap < k) newCap *= 2;
        heap = Arrays.copyOf(heap, newCap);
    }

    static void heapSiftUp(int idx) {
        while (idx > 0) {
            int parent = (idx - 1) >>> 1;
            if (heap[parent] <= heap[idx]) break;
            float tmp = heap[parent]; heap[parent] = heap[idx]; heap[idx] = tmp;
            idx = parent;
        }
    }

    static void heapSiftDown(int size, int idx) {
        while (true) {
            int left = (idx << 1) + 1;
            if (left >= size) break;
            int right = left + 1;
            int smallest = (right < size && heap[right] < heap[left]) ? right : left;
            if (heap[idx] <= heap[smallest]) break;
            float tmp = heap[idx]; heap[idx] = heap[smallest]; heap[smallest] = tmp;
            idx = smallest;
        }
    }

    /**
     * Exact predicate for UBUO >= delta without full sorting.
     * Safe early-pass: once the current top-k sum reaches target, final top-k can only increase.
     */
    static void ensureFilterCache(int n) {
        if (filterStamp.length >= n) return;
        filterStamp = new int[n];
        filterDecision = new byte[n];
    }

    static void nextFilterRound() {
        filterRound++;
        if (filterRound == Integer.MAX_VALUE) {
            Arrays.fill(filterStamp, 0);
            filterRound = 1;
        }
    }

    static boolean pruneByCandidateFilters(int pItem, int extItem, int level, int minSupCount, float delta) {
        ensureFilterCache(GLOBAL_N_ITEMS);
        if (filterStamp[extItem] == filterRound) {
            return filterDecision[extItem] < 0;
        }

        boolean prune = false;
        int a = Math.min(pItem, extItem);
        int b = Math.max(pItem, extItem);

        // Pair-support filter is checked ONCE per candidate X U {extItem}, not per entry.
        if (USE_PAIR_SUPPORT && PAIR_SUPPORT != null) {
            pairSupportChecks++;
            if (PAIR_SUPPORT[a][b - a] < minSupCount) {
                pairSupportPrunedCount++;
                prune = true;
            }
        }

        // Level-1 EUCP materialization filter is also checked once per 2-element candidate.
        // It is not applied at deeper levels.
        if (!prune && USE_EUCP && level == 1 && EUCP != null) {
            if ((EUCP[a][b - a] / minSupCount) < delta - 1e-7f) {
                eucpPrunedCount++;
                prune = true;
            }
        }

        filterStamp[extItem] = filterRound;
        filterDecision[extItem] = (byte) (prune ? -1 : 1);
        return prune;
    }

    static boolean passLazyUBUO(UtilityList ul, int minSupCount, float delta) {
        topKCallCount++;
        int k = minSupCount;
        if (ul.size < k) return false;
        ensureHeap(k);
        int heapSize = 0;
        float topSum = 0f;
        float target = delta * k;

        for (int i = 0; i < ul.size; i++) {
            float v = ul.uo[i] + ul.ruo[i];
            if (heapSize < k) {
                heap[heapSize] = v;
                topSum += v;
                heapSiftUp(heapSize);
                heapSize++;
                if (heapSize == k && topSum + 1e-7f >= target) {
                    topKEarlyPassCount++;
                    return true;
                }
            } else if (v > heap[0]) {
                topSum += v - heap[0];
                heap[0] = v;
                heapSiftDown(heapSize, 0);
                if (topSum + 1e-7f >= target) {
                    topKEarlyPassCount++;
                    return true;
                }
            }
        }
        return heapSize == k && topSum + 1e-7f >= target;
    }

    static void mine(Map<Integer, UtilityList> currentLevel, float delta, int minSupCount, int level) {
        candidateCount += currentLevel.size();

        for (Map.Entry<Integer, UtilityList> mapEntry : currentLevel.entrySet()) {
            int pItem = mapEntry.getKey();
            UtilityList cuoP = mapEntry.getValue();

            if (cuoP.size < minSupCount) continue;

            float avgUo = cuoP.sumUO / cuoP.size;
            if (avgUo >= delta) patternCount++;

            float lubuo = (cuoP.sumUO + cuoP.sumRUO) / minSupCount;
            if (lubuo < delta) continue;

            if (!passLazyUBUO(cuoP, minSupCount, delta)) continue;

            nodeCount++;
            Map<Integer, UtilityList> nextLevel = new LinkedHashMap<>();
            nextFilterRound();

            for (int eIdx = 0; eIdx < cuoP.size; eIdx++) {
                int currItem = cuoP.nItem[eIdx];
                int currIdx = cuoP.nIdx[eIdx];
                int tailCount = 0;

                while (currItem != -1) {
                    ensureScratch(tailCount + 1);
                    scratchItems[tailCount] = currItem;
                    scratchIdxs[tailCount] = currIdx;
                    tailCount++;

                    UtilityList ceList = currentLevel.get(currItem);
                    int nextItem = ceList.nItem[currIdx];
                    int nextIdx = ceList.nIdx[currIdx];
                    currItem = nextItem;
                    currIdx = nextIdx;
                }

                int nextItemNew = -1;
                int nextIdxNew = -1;
                float eUo = cuoP.uo[eIdx];
                float ePUo = cuoP.pUo[eIdx];

                for (int i = tailCount - 1; i >= 0; i--) {
                    int extItem = scratchItems[i];
                    int ceIdx = scratchIdxs[i];
                    UtilityList ceList = currentLevel.get(extItem);

                    if (pruneByCandidateFilters(pItem, extItem, level, minSupCount, delta)) {
                        continue;
                    }

                    UtilityList target = nextLevel.get(extItem);
                    if (target == null) {
                        target = new UtilityList();
                        nextLevel.put(extItem, target);
                    }

                    float newUo = eUo + ceList.uo[ceIdx] - ePUo;
                    float newRuo = ceList.ruo[ceIdx];
                    int insertedIdx = target.add(nextItemNew, newUo, newRuo, nextIdxNew, eUo);
                    generatedEntryCount++;

                    nextItemNew = extItem;
                    nextIdxNew = insertedIdx;
                }
            }

            if (!nextLevel.isEmpty()) mine(nextLevel, delta, minSupCount, level + 1);
        }
    }

    static float[] parseDeltas(String s) {
        String[] parts = s.split(",");
        float[] out = new float[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Float.parseFloat(parts[i].trim());
        return out;
    }

    public static void main(String[] args) throws Exception {
        String dbFile = "java/retail.txt";
        String profitFile = "java/retail_profit.txt";
        float gammaRaw = 0.01f;
        int numRuns = 3;
        float[] deltas = {0.3f};
        String mode = "dual";

        if (args.length >= 1) dbFile = args[0];
        if (args.length >= 2) profitFile = args[1];
        if (args.length >= 3) gammaRaw = Float.parseFloat(args[2]);
        if (args.length >= 4) numRuns = Integer.parseInt(args[3]);
        if (args.length >= 5) deltas = parseDeltas(args[4]);
        if (args.length >= 6) mode = args[5].trim().toLowerCase(Locale.ROOT);

        USE_PAIR_SUPPORT = mode.equals("dual") || mode.equals("ps");
        USE_EUCP = mode.equals("dual") || mode.equals("eucp");
        if (!(mode.equals("dual") || mode.equals("ps") || mode.equals("eucp") || mode.equals("off"))) {
            throw new IllegalArgumentException("mode must be one of: dual, ps, eucp, off");
        }

        float gamma = gammaRaw / 100f;
        int count = 0;

        for (float delta : deltas) {
            System.out.println("\n=== B13F - EUOL-Miner-E2 + candidate-level Pair-Support + level-1 EUCP ===");
            System.out.println("Version: " + VERSION);
            System.out.println("DB: " + dbFile + " | Profit: " + profitFile);
            System.out.println("minSup Ratio: " + gammaRaw + "% | delta: " + delta + " | runs: " + numRuns + " | mode: " + mode);

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
                    for (int[] t : trans) itemSupport.put(t[0], itemSupport.getOrDefault(t[0], 0) + 1);
                    transactions.add(trans);
                }
            }

            int minSupCount = (int) Math.ceil(gamma * transactions.size());

            List<Integer> validItems = new ArrayList<>();
            for (Map.Entry<Integer, Integer> e : itemSupport.entrySet()) {
                if (e.getValue() >= minSupCount) validItems.add(e.getKey());
            }
            validItems.sort((a, b) -> {
                int cmp = Integer.compare(itemSupport.get(a), itemSupport.get(b));
                return cmp != 0 ? cmp : Integer.compare(a, b);
            });

            int nItems = validItems.size();
            GLOBAL_N_ITEMS = nItems;
            ensureFilterCache(nItems);
            Map<Integer, Integer> itemToInt = new HashMap<>();
            for (int i = 0; i < nItems; i++) itemToInt.put(validItems.get(i), i);

            EUCP = new float[nItems][];
            PAIR_SUPPORT = new int[nItems][];
            for (int i = 0; i < nItems; i++) {
                EUCP[i] = new float[nItems - i];
                PAIR_SUPPORT[i] = new int[nItems - i];
            }

            Map<Integer, UtilityList> root = new LinkedHashMap<>();
            for (int i = 0; i < nItems; i++) root.put(i, new UtilityList());

            for (int tid = 0; tid < transactions.size(); tid++) {
                float TU = TUs.get(tid);
                if (TU == 0f) TU = 1f;

                List<int[]> revised = new ArrayList<>();
                for (int[] t : transactions.get(tid)) {
                    Integer mapped = itemToInt.get(t[0]);
                    if (mapped != null) revised.add(new int[]{mapped, t[0], t[1]});
                }
                revised.sort(Comparator.comparingInt(a -> a[0]));

                int rSize = revised.size();
                float[] uoArray = new float[rSize];
                float[] ruoArray = new float[rSize];

                float ruo = 0f;
                int nextItem = -1;
                int nextIdx = -1;
                for (int i = rSize - 1; i >= 0; i--) {
                    int mappedItem = revised.get(i)[0];
                    int rawItem = revised.get(i)[1];
                    int qty = revised.get(i)[2];
                    float uo = (profitDict.get(rawItem) * qty) / TU;
                    uoArray[i] = uo;
                    ruoArray[i] = ruo;

                    UtilityList ul = root.get(mappedItem);
                    int idx = ul.add(nextItem, uo, ruo, nextIdx, 0f);
                    ruo += uo;
                    nextItem = mappedItem;
                    nextIdx = idx;
                }

                // EUCP for level-1 candidate filtering. Since revised is sorted by mapped id,
                // mappedA < mappedB for i < j.
                for (int i = 0; i < rSize; i++) {
                    int mappedA = revised.get(i)[0];
                    for (int j = i + 1; j < rSize; j++) {
                        int mappedB = revised.get(j)[0];
                        PAIR_SUPPORT[mappedA][mappedB - mappedA]++;
                        EUCP[mappedA][mappedB - mappedA] += (uoArray[i] + ruoArray[i]);
                    }
                }
            }

            long buildEnd = System.nanoTime();
            long buildPeak = peakHeapUsed();
            double buildTimeSec = (buildEnd - buildStart) / 1e9;
            double buildPeakMB = mb(buildPeak);

            double totalTime = 0.0;
            double totalPeakTotalMB = 0.0;
            double totalExtraPeakMB = 0.0;
            long finalPatternCount = 0;
            long finalNodeCount = 0;
            long finalCandidateCount = 0;
            long finalGenerated = 0;
            long finalTopK = 0;
            long finalTopKEarly = 0;
            long finalEUCPPruned = 0;
            long finalPSChecks = 0;
            long finalPSPruned = 0;

            for (int run = 1; run <= numRuns; run++) {
                resetCounters();
                forceGC();
                long memBefore = usedMemory();
                resetPeakUsage();
                long start = System.nanoTime();
                mine(root, delta, minSupCount, 1);
                long end = System.nanoTime();
                long peak = peakHeapUsed();

                double timeSec = (end - start) / 1e9;
                double peakTotalMB = mb(peak);
                double extraPeakMB = Math.max(0.0, mb(peak - memBefore));

                totalTime += timeSec;
                totalPeakTotalMB += peakTotalMB;
                totalExtraPeakMB += extraPeakMB;
                finalPatternCount = patternCount;
                finalNodeCount = nodeCount;
                finalCandidateCount = candidateCount;
                finalGenerated = generatedEntryCount;
                finalTopK = topKCallCount;
                finalTopKEarly = topKEarlyPassCount;
                finalEUCPPruned = eucpPrunedCount;
                finalPSChecks = pairSupportChecks;
                finalPSPruned = pairSupportPrunedCount;

                System.out.printf(" - Run %d mining-only: %.3f s | PeakTotal %.2f MB | ExtraPeak %.2f MB\n",
                        run, timeSec, peakTotalMB, extraPeakMB);
            }

            double avgMiningTime = totalTime / numRuns;
            double avgPeakTotal = totalPeakTotalMB / numRuns;
            double avgExtraPeak = totalExtraPeakMB / numRuns;
            double fullTimeOneRun = buildTimeSec + avgMiningTime;
            double fullPeakApprox = Math.max(buildPeakMB, avgPeakTotal);

            System.out.println("(" + (++count) + ") - minSup: " + gammaRaw + "% - delta: " + delta);
            System.out.println("TỔNG MẪU: " + finalPatternCount);
            System.out.println("Nodes: " + finalNodeCount);
            System.out.println("Candidate Count: " + finalCandidateCount);
            System.out.println("Generated Entries: " + finalGenerated);
            System.out.println("UBUO top-k Calls: " + finalTopK);
            System.out.println("UBUO Early Pass: " + finalTopKEarly);
            System.out.println("Pair-Support Checks: " + finalPSChecks);
            System.out.println("Pair-Support Pruned: " + finalPSPruned);
            System.out.println("Level-1 EUCP Pruned: " + finalEUCPPruned);
            System.out.printf("Build Time: %.3f s | Build Peak Heap: %.2f MB\n", buildTimeSec, buildPeakMB);
            System.out.printf("Mining Time Avg: %.3f s | PeakTotal Avg: %.2f MB | ExtraPeak Avg: %.2f MB\n",
                    avgMiningTime, avgPeakTotal, avgExtraPeak);
            System.out.printf("Full Time approx. (Build + Mining Avg): %.3f s | Full Peak approx.: %.2f MB\n",
                    fullTimeOneRun, fullPeakApprox);
        }
    }
}
