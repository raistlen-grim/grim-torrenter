import { splitFileName } from './file-name';

describe('splitFileName', () => {
  it('splits on the last dot', () => {
    expect(splitFileName('movie.final.mkv')).toEqual({ stem: 'movie.final', extension: '.mkv' });
  });

  it('keeps a compound archive extension whole', () => {
    expect(splitFileName('backup.tar.xz')).toEqual({ stem: 'backup', extension: '.tar.xz' });
    expect(splitFileName('backup.TAR.GZ')).toEqual({ stem: 'backup', extension: '.TAR.GZ' });
  });

  it('has no extension for a dotfile or a name without a dot', () => {
    expect(splitFileName('.gitignore')).toEqual({ stem: '.gitignore', extension: '' });
    expect(splitFileName('ubuntu-iso')).toEqual({ stem: 'ubuntu-iso', extension: '' });
  });
});
