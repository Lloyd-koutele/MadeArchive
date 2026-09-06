// PhoneNumberField.tsx — champ téléphone avec sélecteur d'indicatif pays.
//
// Enveloppe react-phone-number-input (appuyé sur libphonenumber-js, la même
// base de règles que Google/WhatsApp) pour : choisir le pays d'abord (menu
// déroulant à drapeaux), puis saisir le numéro national — la bibliothèque
// connaît le nombre de chiffres attendu par pays et empêche la saisie
// au-delà. La valeur stockée/retournée est toujours au format E.164
// (ex. "+221778371057"), quel que soit le pays choisi — fini le mélange de
// formats qu'on avait jusqu'ici (voir UserDto côté serveur).
import PhoneInput from 'react-phone-number-input';
import fr from 'react-phone-number-input/locale/fr.json';
import type { Country } from 'react-phone-number-input';
import 'react-phone-number-input/style.css';
import '../Style/components/PhoneNumberField.css';

export { isValidPhoneNumber } from 'react-phone-number-input';

interface PhoneNumberFieldProps {
    id?: string;
    value: string;
    onChange: (value: string) => void;
    required?: boolean;
    /** Pays présélectionné tant que l'utilisateur n'a pas encore choisi —
     *  Sénégal par défaut, siège de l'organisation. */
    defaultCountry?: Country;
}

function PhoneNumberField({ id, value, onChange, required, defaultCountry = 'SN' }: PhoneNumberFieldProps) {
    return (
        <PhoneInput
            id={id}
            className="phone-number-field"
            international
            defaultCountry={defaultCountry}
            labels={fr}
            placeholder="Numéro de téléphone"
            value={value || undefined}
            onChange={(v) => onChange(v ?? '')}
            required={required}
        />
    );
}

export default PhoneNumberField;
