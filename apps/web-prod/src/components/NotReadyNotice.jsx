/**
 * O aviso que não pode sair da tela.
 *
 * Todo pacote automático sai com `operationalStatus: "not-ready"` e sete bloqueadores, entre eles
 * `physical-height-unvalidated`: nenhuma leitura automática foi comparada com fita métrica. Um
 * painel que esconde isso transforma evidência em ordem de serviço, que é exatamente a confusão
 * que o pipeline inteiro foi desenhado para evitar.
 */
export default function NotReadyNotice() {
  return (
    <div style={{
      display: 'flex', gap: 10, alignItems: 'flex-start', padding: '10px 14px',
      background: 'rgba(202,138,4,0.12)', border: '1px solid rgba(202,138,4,0.35)',
      borderRadius: 'var(--radius-md)', fontSize: 12.5, lineHeight: 1.55, color: '#8a5a00',
    }}>
      <span aria-hidden="true">⚠</span>
      <span>
        <strong>Leituras não validadas.</strong> Nenhuma altura automática foi conferida contra
        fita métrica, e os limiares de 10 e 30 cm ainda não foram aprovados pela Motiva. Estes
        números são evidência para olhar, não instrução para enviar equipe.
      </span>
    </div>
  )
}
