package made.archive.service.importweb;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("unit")
class AdressesInterditesTest
{
    @ParameterizedTest
    @ValueSource(strings = {
        "127.0.0.1", "127.8.9.10", "0.0.0.0", "0.1.2.3",
        "10.0.0.1", "10.255.255.255", "172.16.0.1", "172.17.0.5", "172.31.255.1", "192.168.0.1",
        "169.254.169.254", "169.254.0.1",
        "100.64.0.1", "100.127.255.254",
        "192.0.0.8", "192.0.2.1", "198.18.0.1", "198.19.255.1", "198.51.100.7", "203.0.113.9",
        "224.0.0.1", "239.255.255.250", "240.0.0.1", "255.255.255.255",
        "::1", "::", "fe80::1", "fc00::1", "fd12:3456::1", "ff02::1",
        "2001:db8::1", "2001:0:4136:e378:8000:63bf:3fff:fdd2",
        "::ffff:127.0.0.1", "::ffff:10.1.2.3",
        "64:ff9b::7f00:1", "64:ff9b::a00:1", "2002:7f00:1::1", "2002:0a00:0001::1"
    })
    void refuseLesAdressesInternesPrivesEtReservees(String texte) throws Exception
    {
        assertThat(AdressesInterdites.estInterdite(InetAddress.getByName(texte))).as(texte).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "8.8.8.8", "1.1.1.1", "93.184.216.34", "172.15.255.255", "172.32.0.1", "100.63.255.255", "100.128.0.1",
        "198.17.255.255", "198.20.0.1", "2606:4700:4700::1111", "2a00:1450:4007:80f::200e",
        "64:ff9b::808:808"   // NAT64 vers 8.8.8.8 : légitime
    })
    void accepteLesAdressesPubliques(String texte) throws Exception
    {
        assertThat(AdressesInterdites.estInterdite(InetAddress.getByName(texte))).as(texte).isFalse();
    }

    @ParameterizedTest
    @CsvSource({ "2130706433", "0x7f000001", "017700000001", "127.1" })
    void lesEcrituresDeguiseesSontRamenéesAlaVraieAdresse(String ecriture) throws Exception
    {
        // Le contrôle porte sur l'adresse RÉSOLUE, pas sur le texte du lien.
        assertThat(AdressesInterdites.hoteAutorise(ecriture)).as(ecriture).isFalse();
    }

    @org.junit.jupiter.api.Test
    void unNomImpossibleARésoudreEstRefuse()
    {
        assertThat(AdressesInterdites.hoteAutorise("ce-nom-n-existe-pas.invalid")).isFalse();
    }
}
