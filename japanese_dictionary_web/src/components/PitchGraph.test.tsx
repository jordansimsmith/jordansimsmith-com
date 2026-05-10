import { render, screen, cleanup } from '@testing-library/react';
import { describe, it, expect, afterEach } from 'vitest';
import { PitchGraph, getPitchPattern } from './PitchGraph';

afterEach(() => {
  cleanup();
});

function getMoraPitches(): ('high' | 'low')[] {
  const groups = document.querySelectorAll('g[data-mora-index]');
  return Array.from(groups).map(
    (g) => g.getAttribute('data-pitch') as 'high' | 'low',
  );
}

function getPattern(): string | null {
  const svg = screen.getByRole('img');
  return svg.getAttribute('data-pitch-pattern');
}

describe('getPitchPattern', () => {
  it('returns heiban for pitch 0', () => {
    expect(getPitchPattern('しんぶん', 0)).toBe('heiban');
  });

  it('returns atamadaka for pitch 1', () => {
    expect(getPitchPattern('くる', 1)).toBe('atamadaka');
  });

  it('returns nakadaka for pitch between 2 and N-1', () => {
    expect(getPitchPattern('こころ', 2)).toBe('nakadaka');
  });

  it('returns odaka when pitch equals mora count', () => {
    expect(getPitchPattern('はし', 2)).toBe('odaka');
  });
});

describe('PitchGraph', () => {
  it('renders heiban as low-then-all-high with high particle', () => {
    render(<PitchGraph reading="しんぶん" pitch={0} />);
    expect(getPattern()).toBe('heiban');
    expect(getMoraPitches()).toEqual(['low', 'high', 'high', 'high']);
    const particle = document.querySelector('path[data-particle-pitch]');
    expect(particle?.getAttribute('data-particle-pitch')).toBe('high');
  });

  it('renders atamadaka as high-then-all-low with low particle', () => {
    render(<PitchGraph reading="くる" pitch={1} />);
    expect(getPattern()).toBe('atamadaka');
    expect(getMoraPitches()).toEqual(['high', 'low']);
    const particle = document.querySelector('path[data-particle-pitch]');
    expect(particle?.getAttribute('data-particle-pitch')).toBe('low');
  });

  it('renders nakadaka with the drop on the indicated mora', () => {
    render(<PitchGraph reading="こころ" pitch={2} />);
    expect(getPattern()).toBe('nakadaka');
    expect(getMoraPitches()).toEqual(['low', 'high', 'low']);
  });

  it('renders odaka with high morae and a low particle', () => {
    render(<PitchGraph reading="はし" pitch={2} />);
    expect(getPattern()).toBe('odaka');
    expect(getMoraPitches()).toEqual(['low', 'high']);
    const particle = document.querySelector('path[data-particle-pitch]');
    expect(particle?.getAttribute('data-particle-pitch')).toBe('low');
  });

  it('marks a downstep dot when the pitch falls on the next mora', () => {
    render(<PitchGraph reading="こころ" pitch={2} />);
    const downsteps = document.querySelectorAll('.pitch-graph-dot-downstep');
    expect(downsteps.length).toBe(1);
  });
});
