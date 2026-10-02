import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


class TestNativePeriodicSpectrum(unittest.TestCase):
    def test_incremental_add_remove_and_exact_cdf_parity(self):
        compiler = shutil.which("clang++") or shutil.which("c++")
        if not compiler:
            self.skipTest("native C++ compiler unavailable")
        root = Path(__file__).resolve().parents[1]
        flags = []
        if sys.platform == "darwin":
            sdk = subprocess.check_output(["xcrun", "--show-sdk-path"], text=True).strip()
            flags = ["-isystem", str(Path(sdk) / "usr/include/c++/v1")]
        with tempfile.TemporaryDirectory() as directory:
            harness = Path(directory) / "spectrum.cpp"
            harness.write_text(r'''
#include "periodic_spectrum.h"
#include "traceform_core.h"
#include <cmath>
#include <vector>
#include <limits>
int main() {
    constexpr int states=4, pairs=2000, horizon=132;
    constexpr double pi=3.14159265358979323846;
    PeriodicSpectrum summary(states);
    std::vector<double> observed(pairs*2), queries(horizon*2), prior(states*states,0.25);
    std::vector<int> sources(pairs), targets(pairs);
    for(int pair=0; pair<pairs; ++pair) {
        double fraction=(pair*173%86400)/86400.0;
        observed[pair*2]=2*pi*fraction;
        observed[pair*2+1]=2*pi*((pair*173/86400)%7+fraction)/7;
        sources[pair]=pair%states; targets[pair]=(pair+pair/7)%states;
        if(summary.apply_delta(observed[pair*2],observed[pair*2+1],sources[pair],targets[pair],1)) return 1;
    }
    for(int step=0; step<horizon; ++step) {
        double fraction=(step*4319%86400)/86400.0;
        queries[step*2]=2*pi*fraction; queries[step*2+1]=2*pi*(step%7+fraction)/7;
    }
    std::vector<double> exact(horizon*states*states), actual(exact.size());
    for(int removed : {0,1000,2000}) {
        if(removed) for(int pair=removed-1000;pair<removed;++pair)
            if(summary.apply_delta(observed[pair*2],observed[pair*2+1],sources[pair],targets[pair],-1)) return 2;
        for(int length : {1,7,8,9,15,16,17,20,31,32,33,63,64,65,120,127,128,129,132})
            for(double span : {-1.0,0.01,0.039999,0.040001}) for(double shift : {0.0,0.001,0.1}) {
            for(int step=0;step<horizon;++step) {
                if(span<0) {
                    double fraction=(step*4319%86400)/86400.0;
                    queries[step*2]=2*pi*fraction; queries[step*2+1]=2*pi*(step%7+fraction)/7;
                } else {
                    double week=std::fmod(2*pi-0.001+shift+span*(step/(double)std::max(1,length-1)-0.5)+2*pi,2*pi);
                    queries[step*2]=std::fmod(7*week,2*pi); queries[step*2+1]=week;
                }
            }
            for(int source=0;source<states;++source) for(int target=0;target<states;++target)
                prior[source*states+target]=shift==0.001?(target+1)/10.0:0.25;
            if(summary.cdf(prior.data(),queries.data(),length,actual.data())) return 3;
            if(traceform_periodic_cdf(prior.data(),queries.data(),observed.data()+removed*2,sources.data()+removed,targets.data()+removed,pairs-removed,states,length,exact.data())) return 4;
            for(int index=0;index<length*states*states;++index) if(std::abs(exact[index]-actual[index])>1e-12) return 5;
        }
    }
    if(summary.apply_delta(0,0,0,0,-1)!=-1 || summary.apply_delta(0.1,0,0,0,1)!=-1
       || summary.apply_delta(0,0,states,0,1)!=-1 || summary.apply_delta(0,0,0,0,0)!=-1) return 6;
    queries[0]=std::numeric_limits<double>::quiet_NaN();
    if(summary.cdf(prior.data(),queries.data(),horizon,actual.data())!=-1) return 7;
    return 0;
}
''')
            binary = Path(directory) / "spectrum"
            compiled = subprocess.run([compiler, "-std=c++17", "-O3", "-ffp-contract=off", *flags,
                                       "-I", str(root / "native"), str(root / "native/traceform_core.cpp"),
                                       str(root / "native/periodic_spectrum.cpp"), str(harness), "-o", str(binary)],
                                      capture_output=True, text=True)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            result = subprocess.run([str(binary)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_streamed_and_buffered_monthly_benchmark_outputs_match(self):
        compiler = shutil.which("clang++") or shutil.which("c++")
        if not compiler:
            self.skipTest("native C++ compiler unavailable")
        root = Path(__file__).resolve().parents[1]
        flags = []
        if sys.platform == "darwin":
            sdk = subprocess.check_output(["xcrun", "--show-sdk-path"], text=True).strip()
            flags = ["-isystem", str(Path(sdk) / "usr/include/c++/v1")]
        with tempfile.TemporaryDirectory() as directory:
            binary = Path(directory) / "benchmark"
            subprocess.run([compiler, "-std=c++17", "-O3", "-ffp-contract=off", *flags,
                            "-I", str(root / "native"), str(root / "native/traceform_core.cpp"),
                            str(root / "native/periodic_spectrum.cpp"),
                            str(root / "native/periodic_spectrum_benchmark.cpp"), "-o", str(binary)],
                           check=True, capture_output=True)
            reports = []
            for mode in ["--canonical-monthly-history", "--streamed-monthly-history"]:
                output = subprocess.run([str(binary), mode], check=True, capture_output=True, text=True)
                reports.append([json.loads(line) for line in output.stdout.splitlines()])
            self.assertEqual(reports[0][0], reports[1][0])
            for report in reports:
                self.assertEqual(report[2]["history_pairs"], 86400)
                self.assertTrue(report[2]["mass_conserved"])
                self.assertTrue(report[1]["phase_and_prior_preparation_in_fit_timer"])
                self.assertTrue(report[3]["first_query_coefficient_build_in_warmup"])
                self.assertGreater(report[3]["untimed_warmup_pipeline_ms"], 0)
            self.assertTrue(reports[0][1]["history_arrays_retained"])
            self.assertFalse(reports[1][1]["history_arrays_retained"])
