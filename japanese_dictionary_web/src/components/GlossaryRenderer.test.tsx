import { render, screen, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, afterEach, vi } from 'vitest';
import { GlossaryRenderer } from './GlossaryRenderer';
import type { SCNode } from '../api/client';

afterEach(() => {
  cleanup();
});

describe('GlossaryRenderer', () => {
  it('renders a plain string as text', () => {
    render(<GlossaryRenderer node="hello world" />);
    expect(screen.getByText('hello world')).toBeDefined();
  });

  it('renders an array of children inline', () => {
    const node: SCNode = ['a ', 'b ', 'c'];
    render(<GlossaryRenderer node={node} />);
    expect(screen.getByText(/a b c/)).toBeDefined();
  });

  it('renders ul with li children', () => {
    const node: SCNode = {
      tag: 'ul',
      data: { content: 'glossary' },
      content: [
        { tag: 'li', content: 'first' },
        { tag: 'li', content: 'second' },
      ],
    };
    const { container } = render(<GlossaryRenderer node={node} />);
    const ul = container.querySelector('ul');
    expect(ul).not.toBeNull();
    expect(ul?.getAttribute('data-sc-content')).toBe('glossary');
    const items = container.querySelectorAll('li');
    expect(items).toHaveLength(2);
    expect(items[0].textContent).toBe('first');
  });

  it('renders ruby with rt furigana', () => {
    const node: SCNode = {
      tag: 'ruby',
      content: ['新', { tag: 'rt', content: 'しん' }],
    };
    const { container } = render(<GlossaryRenderer node={node} />);
    const ruby = container.querySelector('ruby');
    expect(ruby).not.toBeNull();
    expect(ruby?.querySelector('rt')?.textContent).toBe('しん');
  });

  it('renders external links in a new tab', () => {
    const node: SCNode = {
      tag: 'a',
      href: 'https://www.edrdg.org/jmwsgi/entr.py?svc=jmdict&q=1',
      content: 'JMdict',
    };
    render(<GlossaryRenderer node={node} />);
    const link = screen.getByRole('link', { name: 'JMdict' });
    expect(link.getAttribute('target')).toBe('_blank');
    expect(link.getAttribute('rel')).toContain('noopener');
  });

  it('invokes onInternalNavigate when an internal link is clicked', async () => {
    const onInternalNavigate = vi.fn();
    const node: SCNode = {
      tag: 'a',
      href: '?query=寺',
      content: '寺',
    };
    render(
      <GlossaryRenderer node={node} onInternalNavigate={onInternalNavigate} />,
    );
    const user = userEvent.setup();
    await user.click(screen.getByRole('link', { name: '寺' }));
    expect(onInternalNavigate).toHaveBeenCalledWith('寺');
  });

  it('also accepts ?q= as the internal-link param', async () => {
    const onInternalNavigate = vi.fn();
    const node: SCNode = {
      tag: 'a',
      href: '?q=新',
      content: '新',
    };
    render(
      <GlossaryRenderer node={node} onInternalNavigate={onInternalNavigate} />,
    );
    const user = userEvent.setup();
    await user.click(screen.getByRole('link', { name: '新' }));
    expect(onInternalNavigate).toHaveBeenCalledWith('新');
  });

  it('renders an image as a placeholder span with description', () => {
    const node: SCNode = {
      tag: 'img',
      src: 'jitendex/graphics/sakura.png',
      alt: 'cherry blossom',
      data: { class: 'gloss-image' },
    };
    const { container } = render(<GlossaryRenderer node={node} />);
    const placeholder = container.querySelector('.gloss-image-placeholder');
    expect(placeholder).not.toBeNull();
    expect(placeholder?.textContent).toContain('cherry blossom');
    expect(container.querySelector('img')).toBeNull();
  });

  it('falls back to the basename when the image has no description', () => {
    const node: SCNode = {
      tag: 'img',
      src: 'jitendex/graphics/something.png',
    };
    const { container } = render(<GlossaryRenderer node={node} />);
    expect(
      container.querySelector('.gloss-image-placeholder')?.textContent,
    ).toContain('something.png');
  });

  it('preserves data attributes as data-sc-* on rendered elements', () => {
    const node: SCNode = {
      tag: 'span',
      data: { class: 'tag', content: 'part-of-speech-info', code: 'n' },
      content: 'noun',
    };
    const { container } = render(<GlossaryRenderer node={node} />);
    const span = container.querySelector('span');
    expect(span?.getAttribute('data-sc-class')).toBe('tag');
    expect(span?.getAttribute('data-sc-content')).toBe('part-of-speech-info');
    expect(span?.getAttribute('data-sc-code')).toBe('n');
    expect(span?.getAttribute('class')).toBeNull();
  });

  it('renders a deeply nested tree in source order', () => {
    const node: SCNode = {
      tag: 'div',
      data: { content: 'sense-group' },
      content: [
        {
          tag: 'span',
          data: { content: 'part-of-speech-info' },
          content: 'noun',
        },
        {
          tag: 'div',
          data: { content: 'sense' },
          content: {
            tag: 'ul',
            data: { content: 'glossary' },
            content: [
              { tag: 'li', content: 'newspaper' },
              { tag: 'li', content: 'paper' },
            ],
          },
        },
      ],
    };
    const { container } = render(<GlossaryRenderer node={node} />);
    const senseGroup = container.querySelector(
      'div[data-sc-content="sense-group"]',
    );
    expect(senseGroup).not.toBeNull();
    const items = container.querySelectorAll('li');
    expect(items).toHaveLength(2);
    expect(items[0].textContent).toBe('newspaper');
    expect(items[1].textContent).toBe('paper');
  });

  it('renders unknown tags as a div fallback', () => {
    const node: SCNode = {
      tag: 'mystery',
      data: { content: 'odd' },
      content: 'hello',
    };
    const { container } = render(<GlossaryRenderer node={node} />);
    const fallback = container.querySelector('div[data-sc-content="odd"]');
    expect(fallback).not.toBeNull();
    expect(fallback?.textContent).toBe('hello');
  });

  it('handles a structured-content wrapper at the top level', () => {
    const node: SCNode = [
      {
        tag: 'div',
        data: { content: 'entry' },
        content: 'wrapped',
      },
    ];
    render(<GlossaryRenderer node={node} />);
    expect(screen.getByText('wrapped')).toBeDefined();
  });
});
