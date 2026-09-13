import { LEVELS, vegetationLevel } from '@greenv/web-core'

/**
 * O que uma leitura sem altura realmente diz.
 *
 * "Não avaliado" cobria dois casos muito diferentes, e a diferença importa para quem despacha
 * equipe. A regra está escrita no próprio código da medição, em
 * `measurement/geometry/grass-height-grid.ts`:
 *
 * > A cell exists here only once some retained grass point landed in it [...] A stretch of road
 * > the camera never faced contributes no cells at all.
 *
 * Ou seja, uma célula só nasce onde a segmentação reteve um ponto de vegetação. Daí os dois
 * casos:
 *
 * - **Células sem evidência, nenhuma medida.** Vegetação foi vista naquelas células e a altura
 *   não pôde ser estabelecida — faltou solo local, faltou voxel, a geometria não fechou. Chamar
 *   isso de "sem vegetação" seria inverter o que aconteceu.
 * - **Nenhuma célula, nem medida nem abstida.** O detector não reteve vegetação em lugar nenhum
 *   da faixa. É o mais perto de "não há mato aqui" que este sistema chega, e ainda assim não é
 *   a mesma afirmação: o mesmo zero sai quando a câmera não apontou para a margem, quando a
 *   profundidade falhou, ou quando o corredor caiu no lugar errado. Por isso o rótulo fala do
 *   detector — "nenhuma vegetação detectada" — e não da margem.
 */
export function readingOf(segment) {
  const level = vegetationLevel(segment?.measurementLevel)
  if (level > 0) {
    return { label: LEVELS[level].label, colour: LEVELS[level].color, background: LEVELS[level].bg, level }
  }

  const measured = segment?.measurementCellsMeasured
  const abstained = segment?.measurementCellsAbstained

  if (measured === 0 && abstained === 0) {
    return {
      label: 'Nenhuma vegetação detectada',
      title: 'A segmentação não reteve vegetação em nenhuma célula da faixa. O mesmo zero '
        + 'aparece quando a câmera não apontou para a margem.',
      colour: LEVELS[0].color,
      background: LEVELS[0].bg,
      level: 0,
    }
  }
  if (measured === 0 && abstained > 0) {
    return {
      label: 'Vegetação vista, sem altura',
      title: `Vegetação foi detectada em ${abstained} ${abstained === 1 ? 'célula' : 'células'}, `
        + 'e em nenhuma a altura pôde ser estabelecida.',
      colour: LEVELS[0].color,
      background: LEVELS[0].bg,
      level: 0,
    }
  }
  return { label: LEVELS[0].label, colour: LEVELS[0].color, background: LEVELS[0].bg, level: 0 }
}
