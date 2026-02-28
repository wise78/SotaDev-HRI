#!/usr/bin/env python3
"""
Embodied Vulnerability Study - Voice and Data Analysis Script
Analyze speech features and questionnaire responses

Usage:
    python analyze_study_data.py
    
Requires:
    pip install librosa soundfile numpy scipy pandas matplotlib seaborn
"""

import os
import numpy as np
import pandas as pd
from pathlib import Path
import warnings
warnings.filterwarnings('ignore')

try:
    import librosa
    import soundfile as sf
except ImportError:
    print("ERROR: Please install required packages:")
    print("pip install librosa soundfile numpy scipy pandas matplotlib seaborn")
    exit(1)


class VoiceAnalyzer:
    """Extract speech features from WAV files"""
    
    def __init__(self, voice_dir='voice'):
        self.voice_dir = voice_dir
        self.features = {}
    
    def analyze_file(self, filepath):
        """
        Extract features from single WAV file
        
        Returns dict with:
        - f0_mean: average pitch (Hz)
        - f0_std: pitch variation
        - f0_range: max pitch - min pitch
        - energy_mean: loudness (dB)
        - energy_std: loudness variation
        - speaking_rate: words per min (estimated from zero crossings)
        - duration: total duration (sec)
        """
        try:
            # Load audio
            y, sr = librosa.load(filepath, sr=None)
            duration = librosa.get_duration(y=y, sr=sr)
            
            # PITCH (F0)
            f0 = librosa.yin(y, fmin=50, fmax=400)  # typical speech range
            f0_valid = f0[f0 > 0]
            f0_mean = np.mean(f0_valid) if len(f0_valid) > 0 else 0
            f0_std = np.std(f0_valid) if len(f0_valid) > 0 else 0
            f0_range = np.max(f0_valid) - np.min(f0_valid) if len(f0_valid) > 0 else 0
            
            # ENERGY (loudness)
            # Convert to dB scale
            S = librosa.feature.melspectrogram(y=y, sr=sr)
            S_db = librosa.power_to_db(S, ref=np.max)
            energy_mean = np.mean(S_db)
            energy_std = np.std(S_db)
            
            # SPEAKING RATE (estimate from zero crossing rate)
            zcr = librosa.feature.zero_crossing_rate(y)[0]
            zcr_mean = np.mean(zcr)
            # Rough estimate: higher ZCR ~= faster speech
            speaking_rate = zcr_mean * 100  # arbitrary scale
            
            # JITTER & SHIMMER (voice quality)
            # Simplified calculation
            f0_diff = np.abs(np.diff(f0_valid))
            jitter = np.mean(f0_diff) / f0_mean if f0_mean > 0 else 0
            
            return {
                'f0_mean': f0_mean,
                'f0_std': f0_std,
                'f0_range': f0_range,
                'energy_mean': energy_mean,
                'energy_std': energy_std,
                'speaking_rate': speaking_rate,
                'jitter': jitter,
                'duration': duration,
                'file': os.path.basename(filepath)
            }
            
        except Exception as e:
            print(f"ERROR processing {filepath}: {e}")
            return None
    
    def analyze_all(self):
        """Process all voice files in directory"""
        results = []
        
        if not os.path.exists(self.voice_dir):
            print(f"Voice directory {self.voice_dir} not found!")
            return
        
        wav_files = sorted(Path(self.voice_dir).glob('*.wav'))
        print(f"Found {len(wav_files)} WAV files")
        
        for wav_file in wav_files:
            print(f"Analyzing: {wav_file.name}...", end=' ')
            features = self.analyze_file(str(wav_file))
            
            if features:
                # Parse filename: P[ID]_[CONDITION]_[TIMESTAMP].wav
                parts = wav_file.stem.split('_')
                if len(parts) >= 2:
                    features['participant'] = parts[0]
                    features['condition'] = parts[1]
                
                results.append(features)
                print("OK")
            else:
                print("FAILED")
        
        self.df_voice = pd.DataFrame(results)
        return self.df_voice
    
    def save_results(self, filename='voice_analysis_results.csv'):
        """Save extracted features to CSV"""
        if hasattr(self, 'df_voice'):
            self.df_voice.to_csv(filename, index=False)
            print(f"Saved to {filename}")
        else:
            print("No voice data analyzed yet")
    
    def summary_by_condition(self):
        """Print summary statistics by condition"""
        if not hasattr(self, 'df_voice'):
            print("Run analyze_all() first")
            return
        
        print("\n" + "="*70)
        print("VOICE ANALYSIS SUMMARY BY CONDITION")
        print("="*70)
        
        for condition in self.df_voice['condition'].unique():
            data = self.df_voice[self.df_voice['condition'] == condition]
            
            print(f"\n{condition.upper()} (n={len(data)})")
            print("-" * 50)
            print(f"Pitch (F0 Mean):     {data['f0_mean'].mean():.1f} ± {data['f0_mean'].std():.1f} Hz")
            print(f"Pitch Variation:     {data['f0_std'].mean():.1f} ± {data['f0_std'].std():.1f} Hz")
            print(f"Energy (Loudness):   {data['energy_mean'].mean():.1f} ± {data['energy_mean'].std():.1f} dB")
            print(f"Speaking Rate:       {data['speaking_rate'].mean():.2f} ± {data['speaking_rate'].std():.2f}")
            print(f"Jitter (quality):    {data['jitter'].mean():.4f} ± {data['jitter'].std():.4f}")
            print(f"Duration (avg):      {data['duration'].mean():.1f} ± {data['duration'].std():.1f} sec")


class QuestionnaireAnalyzer:
    """Analyze post-experiment questionnaire responses"""
    
    def __init__(self):
        self.data = []
    
    def load_questionnaire(self, filepath):
        """
        Load questionnaire responses
        Expects format: participant ID, condition, Q1-Q28 ratings
        """
        try:
            # This is a simplified loader
            # In practice, you might parse the questionnaire text format manually
            # and convert to structured data
            pass
        except Exception as e:
            print(f"Error loading {filepath}: {e}")
    
    def calculate_scales(self, df):
        """
        Calculate composite scores from questionnaire items
        
        Trust (Q1-Q5), Disclosure (Q6-Q10), Empathy (Q11-Q15),
        Perception (Q16-Q20), Intention (Q21-Q23)
        """
        df['trust'] = (df['Q1'] + df['Q2'] + df['Q3'] + df['Q4'] + df['Q5']) / 5
        df['disclosure'] = (df['Q6'] + df['Q7'] + df['Q8'] + df['Q9'] + df['Q10']) / 5
        df['empathy'] = (df['Q11'] + df['Q12'] + df['Q13'] + df['Q14'] + df['Q15']) / 5
        df['perception'] = (df['Q16'] + df['Q17'] + df['Q18'] + df['Q19'] + df['Q20']) / 5
        df['intention'] = (df['Q21'] + df['Q22'] + df['Q23']) / 3
        
        return df


def generate_report(voice_analyzer):
    """Generate analysis report"""
    
    print("\n" + "="*70)
    print("EMBODIED VULNERABILITY STUDY - DATA ANALYSIS REPORT")
    print("="*70)
    print(f"Generated: {pd.Timestamp.now()}")
    print()
    
    if hasattr(voice_analyzer, 'df_voice'):
        df = voice_analyzer.df_voice
        
        print(f"Total voice recordings analyzed: {len(df)}")
        print(f"Unique participants: {df['participant'].nunique()}")
        print(f"Conditions: {', '.join(df['condition'].unique())}")
        print()
        
        voice_analyzer.summary_by_condition()
        
        print("\n" + "="*70)
        print("COMPARISON STATISTICS")
        print("="*70)
        
        # ANOVA-like comparison
        conditions = df['condition'].unique()
        features = ['f0_mean', 'f0_std', 'energy_mean', 'speaking_rate']
        
        for feature in features:
            print(f"\n{feature}:")
            for condition in conditions:
                data = df[df['condition'] == condition][feature]
                print(f"  {condition:12} Mean={data.mean():.2f}  SD={data.std():.2f}  N={len(data)}")
                
        print("\n" + "="*70)
        print("INTERPRETATION GUIDE")
        print("="*70)
        print("""
If UNCERTAIN > CONFIDENT > DISTRESSED for these features:
  * Word count / Duration (longer responses = more disclosure) ✓
  * F0 variation (higher variation = more emotional engagement) ✓
  * Energy variation (more fluctuation = more animated) ✓

If UNCERTAIN > other conditions:
  * Possible support for hypothesis (embodied vulnerability increases disclosure)
  
Opposite pattern:
  * Hypothesis may need revision
  
Mixed results:
  * Effects might be moderated by participant characteristics
  * Check questionnaire scores for confirmation
        """)


def main():
    print("="*70)
    print("EMBODIED VULNERABILITY STUDY - DATA ANALYSIS")
    print("="*70)
    print()
    
    # Analyze voice files
    print("STEP 1: Extracting voice features...")
    print("-" * 70)
    analyzer = VoiceAnalyzer(voice_dir='voice')
    df_voice = analyzer.analyze_all()
    
    if df_voice is not None and len(df_voice) > 0:
        analyzer.save_results('voice_analysis_results.csv')
        generate_report(analyzer)
    else:
        print("No voice files found in 'voice/' directory")
        print("Make sure you've run the experiment and voice files were recorded")
    
    print("\n" + "="*70)
    print("NEXT STEPS")
    print("="*70)
    print("""
1. Load questionnaire scores into questionnaires.csv with columns:
   participant, condition, Q1, Q2, ..., Q28

2. Calculate composite scores (Trust, Disclosure, Empathy, etc.)

3. Perform statistical tests:
   - One-way ANOVA: Condition effect on each measure
   - Post-hoc Tukey HSD if significant
   - Calculate effect sizes (Cohen's d)

4. Correlate voice features with questionnaire scores

5. Code open-ended responses for themes

6. Write up findings and conclusions
    """)


if __name__ == '__main__':
    main()
