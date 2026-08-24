import api from "../api";

export const downloadTemplateCsv = async (typeDocumentIds: number[]): Promise<void> => {
    try {
        const response = await api.post(
            '/editor/docs/template/csv',
            { typeDocumentIds, format: 'csv' },
            { responseType: 'blob' }
        );
        const url = window.URL.createObjectURL(new Blob([response.data]));
        const link = document.createElement('a');
        link.href = url;
        link.setAttribute('download', 'template_import.csv');
        document.body.appendChild(link);
        link.click();
        link.remove();
        window.URL.revokeObjectURL(url);
    } catch (error: any) {
        throw new Error(error.response?.data || error.message || "Erreur téléchargement CSV");
    }
};

export const downloadTemplateExcel = async (typeDocumentIds: number[]): Promise<void> => {
    try {
        const response = await api.post(
            '/editor/docs/template/excel',
            { typeDocumentIds, format: 'excel' },
            { responseType: 'blob' }
        );
        const url = window.URL.createObjectURL(new Blob([response.data]));
        const link = document.createElement('a');
        link.href = url;
        link.setAttribute('download', 'template_import.xlsx');
        document.body.appendChild(link);
        link.click();
        link.remove();
        window.URL.revokeObjectURL(url);
    } catch (error: any) {
        throw new Error(error.response?.data || error.message || "Erreur téléchargement Excel");
    }
};