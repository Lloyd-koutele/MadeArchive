import type { TypeDocumentDto } from '../services/document/TypedocumentService';
import '../Style/document/Typedocument.css';

interface TypeDocumentDetailProps {
    td: TypeDocumentDto;
}

const TYPE_LABELS: Record<string, string> = {
    CHAR: 'Caractère',
    STRING: 'Texte court',
    INTEGER: 'Entier',
    FLOAT: 'Décimal (float)',
    DOUBLE: 'Décimal (double)',
    BOOLEAN: 'Booléen',
    DATE: 'Date',
    TEXT: 'Texte long'
};

function TypeDocumentDetail({ td }: TypeDocumentDetailProps) {
    return (
        <div className="td-detail">

            {/* Infos générales */}
            <div className="td-detail-section">
                <div className="details-row">
                    <strong>Nom :</strong> {td.nom}
                </div>
                <div className="details-row">
                    <strong>Rétention :</strong> {td.retentionYears} an{(td.retentionYears ?? 0) > 1 ? 's' : ''}
                </div>
                <div className="details-row">
                    <strong>Période de grâce :</strong> {td.periodGrace} jour{(td.periodGrace ?? 0) > 1 ? 's' : ''}
                </div>
            </div>

            {/* Métadonnées */}
            <div className="td-detail-section">
                <h4 className="td-detail-subtitle">
                    Métadonnées ({td.metaData?.length ?? 0})
                </h4>

                {!td.metaData || td.metaData.length === 0 ? (
                    <p className="td-detail-empty">Aucune métadonnée définie.</p>
                ) : (
                    <table className="td-meta-table">
                        <thead>
                            <tr>
                                <th>#</th>
                                <th>Nom</th>
                                <th>Type</th>
                                <th>Obligatoire</th>
                            </tr>
                        </thead>
                        <tbody>
                            {td.metaData.map((m, i) => (
                                <tr key={i}>
                                    <td>{i + 1}</td>
                                    <td>{m.nom}</td>
                                    <td>
                                        <span className="td-type-badge">
                                            {TYPE_LABELS[m.metaDataType] || m.metaDataType}
                                        </span>
                                    </td>
                                    <td>
                                        <span className={`td-oblig-badge ${m.obligatoire ? 'yes' : 'no'}`}>
                                            {m.obligatoire ? 'Oui' : 'Non'}
                                        </span>
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                )}
            </div>
        </div>
    );
}

export default TypeDocumentDetail;