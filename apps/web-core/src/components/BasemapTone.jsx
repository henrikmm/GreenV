import { useEffect } from 'react'
import { useMap } from 'react-leaflet'

/**
 * Liga e desliga o cinza da base do mapa.
 *
 * Parece trabalho para a propriedade `className` do `MapContainer`, e era: o `react-leaflet`
 * escreve essa classe uma vez, quando cria o contêiner, e ignora as mudanças seguintes. O
 * resultado é que o botão "Satélite" trocava as telhas mas deixava o filtro cinza por cima, e a
 * imagem de satélite aparecia em preto e branco. Mexer na classe pelo próprio mapa é o caminho
 * que o `react-leaflet` deixa aberto.
 */
export default function BasemapTone({ mono }) {
  const map = useMap()

  useEffect(() => {
    const container = map.getContainer()
    container.classList.toggle('map-mono', Boolean(mono))
  }, [map, mono])

  return null
}
