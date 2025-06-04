# GfxLab


## Nameštanje

- Uključite JAR fajlove iz lib foldera u projekat, ako se to ne desi automatski.
- Potrebno je da vaš projekat koristi JavaFX biblioteke za vaš OS.
  - Najlakši način da to namestite je da koristite BellSoft Liberica JDK, koji, za razliku od većine drugih JDK-ova, dolazi sa ugrađenim JavaFX modulima.
    - Ako koristite IntelliJ, ovo je lako namestiti: File > Project Structure... > Project > SDK > Add SDK > Download JDK... > Vendor: BellSoft Liberica JDK 19.0.1.
    - Alternativno, sami preuzmite JDK sa [https://bell-sw.com/pages/downloads/](https://bell-sw.com/pages/downloads/#/java-19-current). Izaberite vaš OS, poslednju verziju, i Full JDK (jedino Full JDK uključuje JavaFX). Kada instalirate/raspakujete JDK, namestite u IDE-u da projekat koristi baš taj JDK.
  - Ako nećete da koristite BellSoft Liberica JDK, snađite se da preuzmete odgovarajuće biblioteke na neki način (direktni download svih potrebnih jar-fajlova, Maven, ...). Potrebni su vam javafx-base, javafx-controls, javafx-graphics, i javafx-swing.
  - U nekim slučajevima JavaFX neće koristiti GPU za iscrtavanje interfejsa i sve će biti pomalo laggy (meni se to dešava uz Linux i integrisani GPU). U tom slučaju (a ni inače verovatno ne može da škodi), dodajte system property `prism.forceGPU = true`, npr. kroz VM argument `-Dprism.forceGPU=true`.
  

## Šta-gde

- Pokrećete klasu `gui.App`.
- Nameštate šta želite da prikažete u klasi `playground.GfxLab`.
- Sve što budemo razvijali u toku kursa biće u paketu `graphics3d`.


# Master rad todo:
Super, napraviću ti detaljan vodič kako da postaviš razvojno okruženje na Windowsu za povezivanje Java aplikacije iz GfxLab repozitorijuma sa CUDA C++ ray tracer-om preko JCuda ili slične biblioteke. Uključiću preporuke za IDE (VS Code), instalaciju CUDA toolchain-a, i sve korake za pripremu i testiranje jednostavne scene (reflektivne lopte) sa uporedivim CPU i GPU ray tracerima. Javljam se uskoro sa uputstvom.


# Integracija CUDA GPU ray tracera u Java aplikaciju – Tehnički vodič

## 1. Instalacija i podešavanje okruženja na Windows-u

Za uspešno pokretanje CUDA ray tracing koda na GPU iz Java aplikacije, potrebno je najpre pravilno pripremiti razvojno okruženje na Windows platformi. Ova sekcija pokriva instalaciju NVIDIA CUDA alata i drajvera, postavljanje JCuda biblioteke (ili alternativnog načina za pozivanje CUDA koda iz Jave), instalaciju Visual Studio Code editora sa odgovarajućim ekstenzijama, kao i podešavanje Java okruženja (IDE ili VS Code) za rad na projektu.

### 1.1. Instalacija NVIDIA CUDA Toolkit-a i drajvera

1. **Provera GPU podrške:** Uverite se da posedujete NVIDIA GPU koji podržava CUDA (npr. GeForce GTX 1060 ili RTX 3080 su CUDA-sposobni uređaji). Moderni NVIDIA drajveri obično već sadrže podršku za CUDA Driver API (nvcuda.dll). Pre instalacije, ažurirajte NVIDIA drajver na najnoviju verziju odgovarajuću za vašu grafičku kartu (preuzmite sa zvaničnog NVIDIA sajta za drajvere).

2. **Preuzimanje CUDA Toolkit-a:** Posetite NVIDIA Developer stranice i preuzmite odgovarajući **CUDA Toolkit** instalacioni paket za Windows. Izaberite verziju Toolkita koja je kompatibilna sa vašom grafičkom kartom i operativnim sistemom (npr. CUDA 11.x ili 12.x – za RTX 3080 preporučuje se novija verzija koja podržava Ampere arhitekturu). Pokrenite instalaciju i sledite uputstva čarobnjaka:

   * Tokom instalacije, ako već nemate instaliran Visual Studio, *obavezno* uključite instalaciju Microsoft Visual Studio integracije ili potvrdite da je instaliran **MSVC kompilator**. **Napomena:** Iako možete koristiti VS Code kao editor, u pozadini je neophodan Microsoft Visual C++ kompajler zbog načina na koji NVCC (CUDA kompajler) funkcioniše. NVIDIA oficijelno zahteva instaliran Visual Studio ili barem Build Tools, jer NVCC koristi MSVC za kompiliranje C++ delova koda. (Drugim rečima, **morate imati Visual Studio 2019/2022 ili bar "Build Tools" paket kako bi NVCC radio**.)
   * Preporučljivo je prihvatiti podrazumevane opcije za putanje. Installer može ponuditi i opciju instalacije NVIDIA Nsight alata i Primeraka koda – možete ih instalirati za debugging/testiranje, ali nisu neophodni za minimalan rad.

3. **Dodavanje u PATH:** Instalacioni program će obično dodati CUDA alate u vaš *PATH*. Proverite da li je npr. `nvcc` dostupan: otvorite Command Prompt i ukucajte `nvcc --version`. Trebalo bi da dobijete verziju CUDA kompajlera, što potvrđuje da su alatke pravilno instalirane. Ako nije, ručno dodajte npr. `C:\Program Files\NVIDIA GPU Computing Toolkit\CUDA\vX.Y\bin` u sistemsku promenljivu okruženja PATH (zamenite *X.Y* verzijom koju ste instalirali).

4. **Verifikacija instalacije:** Pre prelaska na integraciju sa Javom, preporučuje se testiranje da CUDA radi ispravno. NVIDIA dokumentacija predlaže pokretanje primere **deviceQuery** koji dolazi sa Toolkit-om. Možete otvoriti *Developer Command Prompt* sa Visual Studio alatima, otići u direktorijum `C:\Program Files\NVIDIA GPU Computing Toolkit\CUDA\vX.Y\extras\demo_suite\` (ili u CUDA Samples direktorijum ako ste ih instalirali) i pokrenuti `deviceQuery.exe`. Očekivani izlaz treba da prikaže informacije o vašem GPU (ime uređaja, compute capability, dostupnu memoriju itd.), čime potvrđujete da su drajver i CUDA runtime ispravno postavljeni. Ukoliko `deviceQuery` prijavi da nema CUDA uređaja, proverite da li ste uspešno instalirali drajver i da li sistem prepoznaje GPU.

### 1.2. Instalacija JCuda biblioteke za CUDA-Java integraciju

Da biste pozivali CUDA funkcionalnost iz Jave, postoje dva pristupa: korišćenje gotove **JCuda** biblioteke (Java binding za CUDA API) ili pisanje sopstvene JNI integracije. U ovoj podsekciji fokusiraćemo se na postavljanje **JCuda** biblioteke. (Alternativni JNI pristup biće opisan kasnije.)

**Šta je JCuda?** JCuda je skup Java biblioteka koji omogužava Java kodu da koristi CUDA API funkcije. Postoje Java wrapper-i za CUDA *Driver API* (niski nivo) i *Runtime API* (viši nivo), kao i za neke CUDA biblioteke. JCuda se sastoji od Java .jar fajlova i pratećih nativnih .dll biblioteka koje služe kao most ka stvarnom CUDA driver-u.

**Verzija JCuda i CUDA Toolkit-a:** Važno je preuzeti verziju JCuda koja odgovara verziji CUDA Toolkit-a koju ste instalirali. JCuda verzije su imenovane prema CUDA verziji (postoji striktna veza 1:1 između JCuda i odgovarajuće CUDA verzije). Na primer, za CUDA Toolkit 11.5 treba koristiti JCuda 11.5.x; za CUDA 12.0 tražite da li postoji JCuda 12.x izdanja. Mismatch verzija može prouzrokovati nekompatibilnost (JCuda poziva CUDA funkcije koje možda ne postoje u starijem driver-u).

**Preuzimanje JCuda:** JCuda je otvorenog koda i dostupna je na Maven central repozitorijumu i GitHub-u. Možete je dodati u projekat na jedan od sledećih načina:

* **Preko Maven/Gradle**: Ako koristite Maven ili Gradle build, dodajte zavisnosti za JCuda. Artefakti se nalaze pod grupom `org.jcuda`. Obično će vam trebati bar `jcuda` (ili `jcuda-driver`) i odgovarajući `jcuda-natives` za vaš operativni sistem. Na primer, za Maven u `pom.xml` biste stavili (primer za verziju 11.5.2):

  ```xml
  <dependency>
    <groupId>org.jcuda</groupId>
    <artifactId>jcuda</artifactId>
    <version>11.5.2</version>
  </dependency>
  <dependency>
    <groupId>org.jcuda</groupId>
    <artifactId>jcuda-natives</artifactId>
    <version>11.5.2</version>
    <classifier>windows-x86_64</classifier>
  </dependency>
  ```

  (Classifier označava da uzimamo native .dll za Windows 64-bit.)

* **Ručno preuzimanje jar/dll fajlova**: Sa zvaničnog JCuda GitHub-a ili Maven Central skladišta možete preuzeti JAR pakete. Za Windows 64-bit potrebno je preuzeti npr. `jcuda-<ver>.jar` i `jcuda-natives-<ver>-windows-x86_64.jar`. JAR koji ima *natives* u nazivu sadrži zapravo nativnu biblioteku (.dll). Dodajte ove JAR-ove u vaš projekat (npr. kopirajte u `lib` folder i uključite u classpath projekta). **Napomena:** Kada koristite JCuda, ne morate posebno instalirati CUDA driver biblioteke – one već postoje sa vašim NVIDIA drajverom (nvcuda.dll), a runtime biblioteka (cudart) je deo CUDA Toolkit-a. Potrebno je samo da JCuda .dll može da ih pronađe (o tome obično brine OS ako je CUDA Toolkit uredno instaliran).

**Provera JCuda instalacije:** Nakon dodavanja biblioteka, možete napraviti kratki test program u Javi da proverite da li se JCuda nativna biblioteka učitava. Na primer:

```java
import jcuda.driver.JCudaDriver;
public class TestJCuda {
    static { JCudaDriver.setExceptionsEnabled(true); }
    public static void main(String[] args) {
        JCudaDriver.cuInit(0);
        int count[] = new int[1];
        JCudaDriver.cuDeviceGetCount(count);
        System.out.println("CUDA devices: " + count[0]);
    }
}
```

Ovaj kod inicijalizuje CUDA driver i broji uređaje. Pre pokretanja, postarajte se da JVM zna gde je nativna .dll: možete dodati opciju `-Djava.library.path=<path_do_DLL>` ili jednostavno staviti `jcuda-natives-...jar` u classpath; on bi trebalo automatski da ekstraktuje odgovarajuću .dll u memoriji (JCuda 0.8.0+ ima pojednostavljeno učitavanje native biblioteka). Ako kod izbaci broj uređaja (npr. 1 ako imate jednu NVIDIA GPU), integracija je uspela. U suprotnom, proverite da li dobijate grešku tipa *UnsatisfiedLinkError*: ako da, onda .dll nije pronađen – rešava se time da se osigura da je `jcuda-natives-...jar` na classpath-u ili da se .dll izdvoji u neki folder i putanja prosledi u `java.library.path`.

**Alternativa – JNI sopstvena biblioteka:** Ukoliko ne želite da koristite JCuda, možete direktno praviti JNI pozive ka sopstvenom C++/CUDA kodu. To podrazumeva da ćete implementirati C/C++ funkcije označene sa `JNIEXPORT` i kompilirati ih u .dll, pa ih pozivati iz Jave. Ovaj pristup daje veću kontrolu i potencijalno jednostavniju integraciju specifično prilagođenu vašem ray tracer-u, ali zahteva pisanje native koda. U nastavku vodiča detaljno ćemo objasniti korake i za ovaj pristup (videti odeljak **2. Integracija Java i CUDA koda**).

### 1.3. Instalacija Visual Studio Code i ekstenzija za C++/CUDA

**Visual Studio Code (VS Code)** je preporučeni editor za rad na C++/CUDA delu projekta (a može poslužiti i za Javu uz odgovarajuće ekstenzije). Preuzmite VS Code sa zvaničnog sajta ([https://code.visualstudio.com/](https://code.visualstudio.com/)) i instalirajte ga.

Nakon instalacije VS Code, otvorite *Extensions* panel (Ctrl+Shift+X) i dodajte sledeće ekstenzije za poboljšanje rada sa C++ i CUDA:

* **C/C++ Extension (ms-vscode.cpptools)** – ova zvanična ekstenzija od Microsoft-a obezbeđuje IntelliSense, pregled koda, i osnovnu podršku za pisanje i debagovanje C/C++ koda unutar VS Code. Obavezna je za udobno uređivanje .cpp i .cu fajlova.

* **NVIDIA Nsight Visual Studio Code Edition** – opcionalna, ali korisna ekstenzija od NVIDIA koja dodaje podršku za CUDA razvoj i debagovanje u VS Code. Ona omogućava sintaksno obeležavanje za CUDA kernel fajlove (.cu), auto-dovršavanje za CUDA API, kao i integraciju sa CUDA debagerom i pregled stanja GPU niti pri izvršavanju kernela. Možete je pronaći na Marketplace-u (tražite "Nsight VS Code") i instalirati. Nakon instalacije, dobićete mogućnost postavljanja breakpoints unutar CUDA kernela, što može biti korisno za razvoj i otklanjanje grešaka u vašem GPU kodu.

* **CMake Tools (twxs.cmake)** – *(opciono)* ako planirate da koristite CMake za upravljanje kompilacijom C++/CUDA koda, ova ekstenzija pomaže u konfigurisanju, generisanju i pokretanju CMake build-ova iz VS Code okruženja. Za jednostavne projekte možete i ručno pozivati **nvcc** ili koristiti batch fajl, pa CMake nije neophodan, ali je koristan za veće projekte.

Po instalaciji ekstenzija, preporučuje se da VS Code ponovo pokrenete kako bi se ekstenzije učitale i izvršile inicijalnu konfiguraciju. Potom, kada otvorite .cu ili .cpp fajl, trebalo bi da imate IntelliSense i sintaksno isticanje za CUDA C++ kod.

### 1.4. Podešavanje Java IDE okruženja (IntelliJ IDEA ili VS Code)

Za Java deo projekta možete koristiti poseban IDE ili VS Code sa Java ekstenzijama, u zavisnosti od vaših preferencija:

* **IntelliJ IDEA (preporučeno):** S obzirom da je dati GfxLab projekat već podešen (sadrži *.idea* konfiguracione fajlove), najjednostavnije je koristiti IntelliJ IDEA. Preuzmite **IntelliJ IDEA Community Edition** (besplatan) sa JetBrains sajta i instalirajte ga. Prilikom prvog otvaranja projekta, IntelliJ će prepoznati strukturu. Vodite računa o verziji JDK:

  * Preporuka iz README fajla projekta je da se koristi **BellSoft Liberica JDK** koji uključuje JavaFX module. JavaFX se koristi za GUI aplikacije (projekat koristi JavaFX za prikaz rezultata na ekranu), a Liberica JDK dolazi sa ugrađenim JavaFX, što olakšava konfiguraciju. U IntelliJ-u možete direktno preuzeti Liberica JDK: *File > Project Structure > Project SDK > Add SDK > Download JDK...*, zatim odaberite Vendor: *BellSoft Liberica*, verziju (npr. 17 ili 19 LTS, bitno je da bude *Full* JDK sa JavaFX modulima). IntelliJ će automatski podesiti classpath za JavaFX.
  * Alternativno, ručno preuzmite Liberica JDK sa BellSoft stranice, instalirajte/otpakujte je, i zatim u IDE podesite da projekat koristi taj JDK. Ako ne želite Libericu, možete i bilo koji drugi JDK, ali onda ručno dodajte JavaFX biblioteke (javafx-base, javafx-controls, javafx-graphics, javafx-swing za vašu platformu).
  * Nakon podešavanja JDK/JavaFX, otvorite klasu `gui.App` kako biste pokrenuli aplikaciju (to je glavna klase prema README uputstvu). Ona pokreće JavaFX UI. U klasi `playground.GfxLab` možete birati šta se prikazuje (scenu ili test koji se izvršava). U početku, ostavite CPU varijantu dok ne integrišete GPU.

* **VS Code za Java:** Ako želite da ostanete unutar VS Code za ceo projekat, možete ga osposobiti za Java razvoj instaliranjem **Extension Pack for Java** ekstenzije. Ovaj paket (od Microsoft/RedHat) obuhvata sve potrebno: Java Language Support (IntelliSense, auto-kompletiranje), Debugger for Java, Test Runner, Maven/Gradle integraciju itd.

  * Nakon instalacije, potrebno je da imate instaliran JDK na sistemu (VS Code će ga tražiti). Možete koristiti isti BellSoft Liberica JDK (podesite JAVA\_HOME i dodajte u PATH radi VS Code-a ili kroz VS Code settings odaberite path do JDK).
  * Otvorite projektni folder u VS Code. VS Code će detektovati Java projekat i ponuditi da instalira preporučene ekstenzije (ukoliko već niste). Pri prvom otvaranju Java fajla, Language Server će se pokrenuti – sačekajte da indeksira klasu. Ako koristite JavaFX, možda ćete morati podesiti *launch.json* ili *settings.json* da VS Code prosledi JavaFX module prilikom pokretanja. Na primer, u VS Code launch konfiguraciji dodajte VM argument: `--module-path "path/to/javafx/lib" --add-modules javafx.controls,javafx.swing,javafx.base,javafx.graphics` ako ne koristite Liberica Full JDK.
  * S obzirom da je IntelliJ podešavanje jednostavnije za JavaFX, koristite VS Code samo ako ste već upoznati sa njegovom Java integracijom ili ako vam je pogodnije da sve držite u jednom editoru. U suprotnom, kombinacija IntelliJ (za Java) i VS Code (za C++/CUDA) može biti efikasna.

**Napomena:** Bez obzira na IDE, uverite se da projekt može da se kompajlira i pokrene u CPU režimu pre nego što dodate GPU proširenje. Na primer, pokrenite postojeći CPU ray tracer (ako je implementiran u projektu) i proverite da li dobijate sliku proste scene na ekranu. Ovo baseline ponašanje pomaže da kasnije uporedite vizuelne rezultate i performanse GPU varijante.

## 2. Integracija Java aplikacije sa CUDA kodom (JNI/JCuda)

Sledeći korak je povezivanje Java koda (CPU strana) sa C++/CUDA implementacijom ray tracera (GPU strana). Ova integracija može se ostvariti na dva načina:

* **Korišćenjem JCuda biblioteke (Java binding za CUDA):** U tom slučaju većina posla se obavlja kroz Java API pozive koji prosleđuju CUDA komande (alokacija GPU memorije, pokretanje kernela, itd.) direkno preko JCuda. Vi pišete CUDA kernel(e) u zasebnom .cu fajlu, kompajlirate ih u PTX ili cubin i učitavate ih iz Java koda putem JCuda Driver API-ja.

* **Korišćenjem JNI i sopstvene C++ CUDA biblioteke:** Ovaj pristup podrazumeva da sami implementirate native metodu koja poziva CUDA kernel. Praktčno, napišete C++ kod (unutar .cu fajla) koji obavlja sav posao (alokacija, izvršavanje kernela, kopiranje rezultata), kompajlirate to u .dll, a u Javi pozivate tu native funkciju. Java i C++ komunikacija ide preko JNI interfejsa.

Oba pristupa mogu dati isti rezultat. Koji ćete odabrati zavisi od ličnih preferencija i iskustva. **JCuda** može biti brži za postaviti jer ne zahteva pisanje JNI "lepljivog" koda – već koristite postojeći API. **JNI sopstveni kod** daje vam fleksibilnost da iskoristite punu moć C++ (npr. lakše pravljenje kompleksnijih struktura podataka, korišćenje drugih biblioteka kao OptiX ili sl, ako zatreba) i precizno kontrolišete tok izvršavanja. U nastavku ćemo predstaviti obe varijante.

### 2.1. Korišćenje JCuda biblioteke – povezivanje putem Driver API

Ako ste odabrali JCuda put, pretpostavljamo da ste već dodali JCuda jar i native biblioteke u projekat (vidi sekciju 1.2). Sada je potrebno da vaš Java kod iskoristi JCuda za pokretanje ray tracing kernela na GPU.

**Priprema CUDA kernela:** Najpre, napišite CUDA kernel koji će računati boju svakog piksela vaše slike (okvira). To će biti funkcija u C/C++ sa `__global__` oznakom, npr.:

```cpp
// RayTracerKernels.cu
#include <cuda_runtime.h>
// (plus eventualno vaše definicije struktura scene koje ćete kopirati)
__device__ float3 rayTracePixel(int px, int py, SceneData scene) {
    // računanje boje za dati pixel (px, py) traženjem preseca zraka sa sferama, računanje osvetljenja itd.
    // ... (ovo je core ray tracing kod, prilagođen za izvođenje na GPU)
}

__global__ void renderKernel(float3* outputColors, SceneData scene, int width, int height) {
    int x = blockIdx.x * blockDim.x + threadIdx.x;
    int y = blockIdx.y * blockDim.y + threadIdx.y;
    if(x < width && y < height) {
        int idx = y * width + x;
        float3 color = rayTracePixel(x, y, scene);
        outputColors[idx] = color;
    }
}
```

*(Napomena: `float3` može biti definisan preko CUDA-inih vektorskih tipova ili možete koristiti običnu strukturu. `SceneData` je struktura koja opisuje vaše objekte u sceni – npr. niz sfera, svetla, itd. Ideja je da scene podatke pre kopiranja na GPU upakujete u takvu strukturu.)*

Kompajlirajte ovaj .cu fajl u PTX modul. Ovo možete uraditi **offline** (pre pokretanja Java programa) ili **dinamički** iz Java koda:

* *Offline:* Pokrenite NVCC ručno ili putem batch skripte. Npr, iz komandne linije:

  ```
  nvcc -arch=sm_61 -arch=sm_86 -ptx RayTracerKernels.cu -o RayTracerKernels.ptx
  ```

  Ovde smo naveli dve arhitekture (sm\_61 za GTX 1060 i sm\_86 za RTX 3080) tako da PTX sadrži kod kompatibilan sa oba GPU-a. Dobićete fajl *RayTracerKernels.ptx*. Ovaj fajl ćete kasnije učitati iz Java programa.
* *Dinamički:* JCuda omogućava da u runtime-u kompajlirate .cu u PTX korišćenjem NVCC ili NVRTC. Primer iz JCuda tutorijala koristi poseban metod `preparePtxFile()` koji u pozadini poziva nvcc proces. Ovaj pristup zahteva da je NVCC dostupan u PATH i može usporiti pokretanje (jer svako pokretanje aplikacije pokreće kompajliranje kernela). Za razvoj je korisno, ali za stabilnu verziju bolje je prethodno iskompajlirati i distribuirati PTX uz aplikaciju.

**Učitavanje kernela preko JCuda (Driver API):** U Java kodu, pređite u JCuda *Driver API* (paket `jcuda.driver.*`). Glavni koraci su:

1. Inicijalizacija i kontekst:

   ```java
   JCudaDriver.setExceptionsEnabled(true);
   JCudaDriver.cuInit(0);
   CUdevice device = new CUdevice();
   JCudaDriver.cuDeviceGet(device, 0); // uzimamo prvi GPU uređaj
   CUcontext context = new CUcontext();
   JCudaDriver.cuCtxCreate(context, 0, device);
   ```

   Ovo bira GPU i kreira CUDA kontekst za izvršavanje kernela na tom GPU.

2. Učitavanje PTX modula i dohvat kernela:

   ```java
   CUmodule module = new CUmodule();
   JCudaDriver.cuModuleLoad(module, "RayTracerKernels.ptx");
   CUfunction renderKernel = new CUfunction();
   JCudaDriver.cuModuleGetFunction(renderKernel, module, "renderKernel");
   ```

   Pretpostavljamo da se PTX fajl *RayTracerKernels.ptx* nalazi u radu aplikacije. Alternativno, putanju možete načiniti apsolutnom ili učitati kao resurs. Nakon učitavanja, dobijamo handle za kernel funkciju po imenu.

3. Alokacija memorije na GPU i kopiranje podataka scene:

   * Pripremite podatke scene u Javi. Na primer, imajte listu sfera (svaka definisana centrom, radijusom, bojom, reflektivnošću). Napravite niz ili strukturu koja sadrži sve to.
   * Alocirajte GPU memoriju za taj niz:

     ```java
     CUdeviceptr d_spheres = new CUdeviceptr();
     int size = numSpheres * SizeofSphere; // računajte bajtove; SizeofSphere je veličina jedne sfere u bajtima
     JCudaDriver.cuMemAlloc(d_spheres, size);
     ```
   * Kopirajte podatke:

     ```java
     JCudaDriver.cuMemcpyHtoD(d_spheres, Pointer.to(hostSpheresArray), size);
     ```
   * Slično alocirajte prostor za izlazne piksele (boje piksela): npr. `CUdeviceptr d_outputColors` veličine width*height*sizeof(float3).

   *(Napomena: U ovom primeru rukujemo niskonivo memorijom. Alternativno, možete koristiti **JCuda Runtime API** (paket `jcuda.runtime.*`) koji ima nešto lakši sintaksu za alokaciju (`cudaMalloc`) i kopiranje (`cudaMemcpy`), ali ne i dinamičko učitavanje kernela. Kombinacija je moguća: npr. kerneli pre-kompajlirani i linkovani u .dll pa zvani runtime API, ili koristiti driver API za module. Ovde ostajemo u okviru Driver API-ja radi kompletne fleksibilnosti učitavanja PTX-a.)*

4. Podešavanje parametara i lansiranje kernela:

   * Odredite dimenzije grida i blokova. Na primer, blok od 16x16 niti i grid koji pokrije sliku:

     ```java
     int blockSizeX = 16, blockSizeY = 16;
     int gridSizeX = (width + blockSizeX - 1) / blockSizeX;
     int gridSizeY = (height + blockSizeY - 1) / blockSizeY;
     ```
   * Pripremite argumente za kernel poziv. JCuda Driver API zahteva da argumente predamo kao niz `Pointer` objekata:

     ```java
     Pointer kernelParameters = Pointer.to(
         Pointer.to(d_outputColors),
         Pointer.to(new int[]{width}),
         Pointer.to(new int[]{height}),
         Pointer.to(d_spheres),
         Pointer.to(new int[]{numSpheres})
         // ... plus eventualno drugi elementi scene, svetla itd.
     );
     ```
   * Pokrenite kernel:

     ```java
     JCudaDriver.cuLaunchKernel(renderKernel,
         gridSizeX, gridSizeY, 1,      // grid dimenzije
         blockSizeX, blockSizeY, 1,    // block dimenzije
         0, null,                      // shared memory size, stream
         kernelParameters, null
     );
     JCudaDriver.cuCtxSynchronize();   // sačekaj da kernel završi
     ```

     Ovim se kernel izvršava paralelno na GPU. `cuCtxSynchronize` blokira dok se sve niti ne završe.

5. Prenos rezultata nazad na CPU:
   Nakon izvršavanja, rezultat (boje piksela) se nalaze u GPU baferu `d_outputColors`. Potrebno ih je prebaciti u Java niz da bismo mogli prikazati na ekranu:

   ```java
   float[] hostColors = new float[width * height * 3]; // ako smo koristili float3 kao tri float vrednosti
   JCudaDriver.cuMemcpyDtoH(Pointer.to(hostColors), d_outputColors, hostColors.length * Sizeof.FLOAT);
   ```

   Sada `hostColors` sadrži niz vrednosti boja. Te vrednosti treba konvertovati u format pogodan za prikaz. Ako su to RGB float 0-1 vrednosti, pretvorite ih u 0-255 range i u int (ARGB) format za JavaFX Canvas ili Image:

   ```java
   WritableImage img = new WritableImage(width, height);
   PixelWriter pw = img.getPixelWriter();
   // pretpostavimo linearan raspored hostColors: (r,g,b) ponavlja se
   for(int y=0; y<height; y++){
       for(int x=0; x<width; x++){
           int i = (y*width + x) * 3;
           int r = (int)(clamp(hostColors[i] * 255));
           int g = (int)(clamp(hostColors[i+1] * 255));
           int b = (int)(clamp(hostColors[i+2] * 255));
           int color = (0xFF<<24) | (r<<16) | (g<<8) | b;
           pw.setArgb(x, y, color);
       }
   }
   ```

   Gde je `clamp` funkcija da ograniči vrednost 0-255. Konačno, taj `img` se može prikazati u JavaFX ImageView ili Canvas. U kontekstu GfxLab projekta, verovatno postoji mesto gde se traži od scene slika za prikaz – tu integrisite ovaj kod.

6. **Čišćenje:** Oslobodite GPU memoriju nakon korišćenja:

   ```java
   JCudaDriver.cuMemFree(d_outputColors);
   JCudaDriver.cuMemFree(d_spheres);
   JCudaDriver.cuCtxDestroy(context);
   ```

   Ovo je važno da bi se resursi oslobodili uredno (mada kad proces završi, driver će ionako počistiti, ali bolje eksplicitno).

Ovime smo prošli glavne korake za JCuda integraciju. Prednost je što sve ostaje unutar Java procesa, bez potrebe pravljenja posebne native biblioteke – koristimo postojeću JCuda .dll koja internim JNI pozivima komunicira s CUDA driverom.

### 2.2. Korišćenje JNI – sopstvena C++/CUDA biblioteka

Drugi pristup je da sami napišemo C++ kod koji poziva CUDA i da ga povežemo sa Javom putem JNI. Ovaj put zaobilazi JCuda biblioteku; umesto nje, vi kreirate svoju .dll sa potrebnim funkcijama.

**Korak 1: Definisanje JNI interfejsa u Javi.** Odlučite gde u Java kodu želite da pozivate GPU akceleraciju. Npr. možete napraviti klasu `Graphics3D.RendererGPU` sa native metodama:

```java
package xyz.marsavic.gfxlab.graphics3d;
public class RendererGPU {
    // Učitavanje native biblioteke
    static { System.loadLibrary("raytracer_gpu"); }
    // Native metoda za renderovanje jedne slike
    public static native void renderImage(int width, int height, float[] spheresData, int numSpheres, float[] outColors);
}
```

Ova metoda `renderImage` će u C++ uraditi isto što i JCuda sekvenca: uzeti dimenzije, podatke o sferama i popuniti niz boja. Primetimo da prosleđujemo referencu na Java nizove (što JNI može da mapira ka pointerima).

**Korak 2: Implementacija C++/CUDA funkcije.** Koristeći JDK alat (javac) generišite JNI header za ovu klasu:

```
javac RendererGPU.java
javac -h . RendererGPU.java 
```

Ovo će kreirati C/C++ header fajl, verovatno nazvan `xyz_marsavic_gfxlab_graphics3d_RendererGPU.h`, sa deklaracijom JNI funkcije:

```cpp
/* Header generated by javac */
#include <jni.h>
JNIEXPORT void JNICALL Java_xyz_marsavic_gfxlab_graphics3d_RendererGPU_renderImage
  (JNIEnv* env, jclass clazz, jint width, jint height, jfloatArray spheresData, jint numSpheres, jfloatArray outColors);
```

Sada kreirajte .cu fajl (ili .cpp fajl uz NVCC) gde ćete implementirati ovu funkciju. Na primer `RayTracerGPU.cu`:

```cpp
#include <jni.h>
#include "xyz_marsavic_gfxlab_graphics3d_RendererGPU.h"
#include <cuda_runtime.h>
// Definicije struktura za sferu, scene itd. 
struct Sphere { float x,y,z,radius; /*... plus boja, reflektivnost*/ };
// Kernel sličan ranije opisanom:
__device__ float3 rayTracePixel(int px, int py, Sphere* spheres, int numSpheres) { ... }
__global__ void renderKernelGPU(Sphere* d_spheres, int numSpheres, float3* d_out, int width, int height) {
    int x = blockIdx.x * blockDim.x + threadIdx.x;
    int y = blockIdx.y * blockDim.y + threadIdx.y;
    if(x < width && y < height) {
        int idx = y*width + x;
        float3 color = rayTracePixel(x, y, d_spheres, numSpheres);
        d_out[idx] = color;
    }
}
JNIEXPORT void JNICALL Java_xyz_marsavic_gfxlab_graphics3d_RendererGPU_renderImage
  (JNIEnv* env, jclass clazz, jint width, jint height, jfloatArray spheresData, jint numSpheres, jfloatArray outColors) {
    // 1. Uzmi podatke iz Java nizova
    jsize spheresLen = env->GetArrayLength(spheresData);
    std::vector<float> h_spheres(spheresLen);
    env->GetFloatArrayRegion(spheresData, 0, spheresLen, h_spheres.data());
    // pretvori h_spheres (flat float array) u polja Sphere struktura:
    int n = numSpheres;
    std::vector<Sphere> hostSpheres(n);
    for(int i=0; i<n; ++i) {
        hostSpheres[i].x = h_spheres[i*ElemCount + 0];
        hostSpheres[i].y = h_spheres[i*ElemCount + 1];
        // ... (popuni ostala polja)
    }
    // 2. Alokacija GPU memorije
    Sphere* d_spheres;
    cudaMalloc(&d_spheres, n * sizeof(Sphere));
    cudaMemcpy(d_spheres, hostSpheres.data(), n * sizeof(Sphere), cudaMemcpyHostToDevice);
    float3* d_out;
    cudaMalloc(&d_out, width * height * sizeof(float3));
    // 3. Lansiraj kernel
    dim3 block(16,16);
    dim3 grid((width+15)/16, (height+15)/16);
    renderKernelGPU<<<grid, block>>>(d_spheres, n, d_out, width, height);
    cudaDeviceSynchronize();
    // 4. Kopiraj rezultat nazad
    size_t nPixels = (size_t)width * height;
    std::vector<float3> hostOut(nPixels);
    cudaMemcpy(hostOut.data(), d_out, nPixels * sizeof(float3), cudaMemcpyDeviceToHost);
    // 5. Osveži Java izlazni niz 
    jsize outLen = env->GetArrayLength(outColors); // trebalo bi width*height*3
    // Kopiramo float3 vektore u linearni niz float-a
    std::vector<float> hostOutFlat(outLen);
    for(size_t i=0; i<nPixels; ++i) {
        hostOutFlat[i*3 + 0] = hostOut[i].x;
        hostOutFlat[i*3 + 1] = hostOut[i].y;
        hostOutFlat[i*3 + 2] = hostOut[i].z;
    }
    env->SetFloatArrayRegion(outColors, 0, outLen, hostOutFlat.data());
    // 6. Očisti GPU resurse
    cudaFree(d_out);
    cudaFree(d_spheres);
}
```

*(Gornji kod je ilustrativan – prilagodite ga strukturi vaših podataka. Takođe, potrebno je definisati `ElemCount` kao broj float vrednosti po sferi u *spheresData* nizu: npr. ako čuvate x,y,z,radius,r,g,b,reflektivnost, onda ElemCount = 8.)*

Obratite pažnju da smo koristili **CUDA Runtime API** (cudaMalloc, cudaMemcpy itd.) unutar C++ koda za jednostavnost, pošto sve pišemo u .cu. To znači da će naša .dll morati biti povezana sa CUDA runtime bibliotekom (cudart). *Dve opcije:* linkovati cudart *statički* (što će ugraditi runtime kod u .dll – dozvoljeno je novijim verzijama CUDA) ili *dinamički* (što zahteva da ciljni sistem ima `cudart64_X.dll`, obično se isporučuje uz CUDA Toolkit; možete ga i priložiti uz aplikaciju prema NVIDIA EULA). Najjednostavnije je osloniti se na to da je CUDA Toolkit instaliran na sistemu gde pokrećete (ili bar redistribuirati cudart .dll uz svoj .jar/dll).

**Korak 3: Kompajliranje .dll biblioteke:**
Kada ste implementirali `RayTracerGPU.cu`, potrebno je da ga kompajlirate u .dll za Windows:

* Otvorite *x64 Native Tools Command Prompt* (koji dolazi sa Visual Studio) da bi NVCC imao pristup MSVC linkeru.
* Navigirajte do foldera gde je kod i pokrenite NVCC sa odgovarajućim opcijama. Na primer:

  ```
  nvcc -arch=sm_61 -arch=sm_86 -shared -Xcompiler "/MT" -I"%JAVA_HOME%\include" -I"%JAVA_HOME%\include\win32" RayTracerGPU.cu -o raytracer_gpu.dll
  ```

  Objašnjenje parametara:

  * `-arch=sm_61 -arch=sm_86` – kompajliramo za Pascal (Compute 6.1) i Ampere (8.6) arhitekture, tako da dobijeni binarni kod podržava GTX 1060 i RTX 3080 optimano. (Možete dodati i druge arh po potrebi.)
  * `-shared` – pravimo deljenu biblioteku (.dll).
  * `-Xcompiler "/MT"` – opcija prosleđena MSVC linkeru da statički linkuje CRT (C runtime). Ovo izbegava zavisnost od MSVC runtime DLL-ova, ali nije obavezno.
  * `-I"%JAVA_HOME%\include" -I"%JAVA_HOME%\include\win32"` – dodajemo JNI header fajlove (prilagodite JAVA\_HOME putanju za vaš JDK).
  * `-o raytracer_gpu.dll` – naziv izlazne biblioteke. Vodite računa da `System.loadLibrary("raytracer_gpu")` u Javi traži library po imenu bez prefiksa i ekstenzije, tj. `raytracer_gpu` će odgovarati `raytracer_gpu.dll` na Windows-u.
  * Podrazumevano, nvcc će linkovati cudart biblioteke dinamički. Ako želite statički link, dodajte `-cudart static` flagu nvcc-u. Za početak, dinamički je u redu – zahteva da je `cudart64_x.y.dll` dostupna u PATH-u ili pored .dll.

Ako je kompilacija uspešna, dobićete `raytracer_gpu.dll`. Smestite taj fajl u folder gde će ga JVM tražiti. Najlakše: stavite ga pored .jar vašeg projekta ili dodajte putanju do njega u `java.library.path` (možete npr. programatski: `System.setProperty("java.library.path", "...")` pre `loadLibrary` poziva, ili postaviti kao VM argument).

**Korak 4: Pozivanje iz Jave:**
Sada, modifikujte Java kod da iskoristi ovu native funkciju:

* U klasi `RendererGPU` već smo definisali `static { System.loadLibrary("raytracer_gpu"); }`. Ovo će prilikom prvog pristupa klasi pokušati da učita `raytracer_gpu.dll`. Uverite se da JVM nalazi .dll (ako ne, dobićete UnsatisfiedLinkError na toj liniji).
* Gde god da želite da renderujete sliku na GPU, pozovite:

  ```java
  float[] spheresData = ...; // pripremljen niz parametara sfera (moraju odgovarati onom što C++ očekuje)
  float[] outColors = new float[width * height * 3];
  RendererGPU.renderImage(width, height, spheresData, numSpheres, outColors);
  ```

  Nakon povratka iz native metode, niz `outColors` će biti popunjen RGB vrednostima u rasponu \[0,1] (ili \[0,255] zavisno kako ste implementirali – prema gore našem kodu, to je \[0,1]). Kao i kod JCuda varijante, potrebno je te vrednosti pretvoriti u odgovarajući format piksela i nacrtati na ekranu. Postupak je isti: iterirajte kroz piksele i koristite `PixelWriter` da upišete boje u Canvas ili Image komponentu.

Ako je sve prošlo kako treba, trebalo bi da vidite identičnu sliku kao kod CPU renderera, samo generisanu od strane GPU-a.

**Prednost JNI pristupa:** Ne zavisite od spoljne Java biblioteke (JCuda) i imate punu kontrolu nad time šta radite u C++/CUDA kodu. Možete iskoristiti optimizacije, koristiti C++ biblioteke (npr. možete iskoristiti Thrust za lakše manipulacije nizovima na GPU, ili OptiX engine – o tome više u nastavku) i lakše upravljati kompleksnom logikom. Takođe, kod sa strane Jave ostaje čitkiji – jednom kada ste napisali i testirali native deo, Java poziv je jednostavan (jedna metoda).

**Mana:** Svaka promena u CUDA kodu zahteva ponovno kompajliranje .dll, i debugovanje može biti teže (mada Nsight VS Code ekstenzija može da debuguje i JNI poziv, jer zapravo možete pokrenuti Java program u debug modu, a zatim prikačiti CUDA debugger na kernel – zahtevnija postavka). Takođe, morate voditi računa o stvarima poput upravljanja memorijom ručno, prevođenja Java objekata u C strukture i nazad, itd.

### 2.3. Organizacija projekta i integracija sa postojećim kodom

Sada kada imate tehnički način za izvršavanje ray tracing koda na GPU, važno je pravilno organizovati kod tako da se uklopi u arhitekturu postojeće aplikacije (**GfxLab** projekt). Cilj je da JavaFX GUI koristi isti mehanizam prikaza kao i do sada, samo što će podaci o bojama piksela doći ili iz CPU računanja ili iz GPU računanja.

Evo preporuka za organizaciju projekta:

* **Struktura koda:** Unutar paketa `graphics3d` (ili pod-paketa) dodajte novu klasu za GPU renderer. Na primer, možete imati interfejs ili apstraktnu klasu `Renderer` koja ima metodu `render(Scene scene, ImageCanvas canvas)` ili slično, pa onda dve implementacije: `RendererCPU` (postojeća logika) i `RendererGPU`. Ukoliko kursni kod to ne predviđa, možete i jednostavno u postojeću klasu za renderiranje ugraditi opciju za izbor načina.
* **Scene opis:** Pojednostavite scenu za potrebe testiranja GPU-a. Preporučeno je da za početak koristite minimalnu scenu (par sfera i jedan izvor svetla) i **iste podatke prosledite i CPU i GPU rendereru**. To znači da treba definisati strukturu scene nezavisno od renderera:

  * Na primer, kreirajte klasu `Scene` koja sadrži listu objekata (`List<Solid>` ili konkretno listu `Sphere` objekata ako su samo lopte) i listu izvora svetla. Sfera klasa ima atribute položaj, radijus, materijal (gde materijal može definisati boju, reflektivnost itd.).
  * Obezbedite metodu da iz `Scene` ekstraktujete raw podatke za GPU (niz float vrednosti kao `spheresData`). To može biti metod npr. `scene.toFloatArray()` koji pakuje svaku sferu u nekoliko float-ova kako smo radili u JNI delu.
  * CPU renderer može direktno iterirati kroz `scene.getSpheres()` listu, a GPU renderer će uzeti `scene.toFloatArray()` i proslediti JNI/JCuda pozivu. Ovako ste sigurni da obe verzije renderera vide istu scenu.
* **Platno za prikaz:** U GUI delu aplikacije (JavaFX), verovatno postoji komponenta (Canvas ili ImageView) gde se crta rezultat. Cilj je da *ne pravite dve odvojene UI logike* za CPU i GPU; umesto toga, možete dodati opciju ili preklopnik. Na primer, dodajte u UI meniju ili preko dugmeta opciju "Use GPU". Kada je aktivna, pozvaće se `RendererGPU.render(scene, image)` umesto CPU petlje.

  * Jednostavnija varijanta za testiranje: napravite dva različita *playground* scenarija – jedan koji poziva CPU render pa prikaže, drugi GPU render pa prikaže. Možete ručno menjati koju klasu pokrećete. Npr. u `playground.GfxLab` klasi, tamo gde se bira šta se prikazuje, dodajte statički blok koji ili instancira CPU ili GPU renderer.
  * U svakom slučaju, iskoristite isti mehanizam za prikaz slike: oba renderera treba da daju neki oblik bitmape ili niza piksela koji onda GUI nacrta. Idealno, napravite da obe implementacije vraćaju `WritableImage` ili popunjavaju zajednički bafer.
* **Deljenje koda:** Mnoge delove ray tracing logike možete pokušati da podelite između CPU i GPU varijante kako biste osigurali isti rezultat. Npr. proračun refleksije, senki, itd. – možete definisati kao metode koje se eventualno koriste i u Javi i u C++ (premda dupliranje koda na dva jezika je teško izbeći). U najmanju ruku, držite parametre isti (npr. isti broj refleksivnih odbijanja, ista boja svetla, itd.).

Pošto je fokus rada poređenje performansi, nije neophodno da GPU renderer podržava sve složenosti koje možda CPU kod ima (poput raznih tipova objekata). U dogovoru sa mentorom, možete se ograničiti na *samo sfere* i jednostavno osvetljenje bez komplikovanih efekata za inicijalnu implementaciju, kako biste lakše ispravili sve probleme i izmerili ubrzanje. Kasnije možete proširiti mogućnosti.

## 3. Primer jednostavne scene za testiranje performansi

Kada ste uspostavili GPU render, potrebno je testirati ga i uporediti sa CPU verzijom. Za fer poređenje, definisaćemo jednostavnu scenu koju obe implementacije mogu da podrže i koja opterećuje ray tracer dovoljno da razlike u brzini dođu do izražaja.

**Predložena scena: "Reflektivne sfere na podu":**

* **Objekti:** Tri lopte (sfere) postavljene iznad ravne podloge.

  * Sfera 1: Poluprečnik \~1, pozicija (0,1,0). Materijal reflektivan (npr. ogledalo).
  * Sfera 2: Poluprečnik \~1, pozicija (-2,1,3). Materijal difuzan (npr. obojena mat površina).
  * Sfera 3: Poluprečnik \~1, pozicija (2,1,3). Materijal staklast (ako želite testirati refrakciju) ili takođe reflektivan, u zavisnosti od toga da li ste implementirali refrakciju.
* **Podloga:** Beskonačna ravan (plane) koja predstavlja pod, npr. y=0 ravnina, sa blagim reflektivnim ili difuznim svojstvom. (Ako je implementacija ravni komplikovana na GPU, možete improvizovati veliki disk ili kvadrat od sfera, ali plane je poželjan za pravi ray tracing – može se dodati formula za ray-plane presjek.)
* **Svetlo:** Tačkasti izvor svetla (point light) postavljen iznad scene, npr. na koordinati (0, 5, 0) ili (0, 5, -5), bele boje. Ovo svetlo će omogućiti da sfere bacaju senke na pod i jedna na drugu, što je dobar test za ray tracing. Možete implementirati proste senke prateći sekundarni zrak ka svetlu.
* **Kamera:** Postavite kameru tako da gleda ka sferama. Npr. sa pozicije (0, 2, -10) gledajući ka (0,1,0). Koristite perspektivnu projekciju.

Ova scena je dovoljno bogata: ima refleksije (sfera poput ogledala će reflektovati drugu sferu i pod), ima senke (sfere će praviti senke na podu), a geometrija je jednostavna (sfere i ravan). Nema trouglova niti kompleksnih modela, što smo i želeli izbeći za početak.

Možete naravno promeniti raspored i materijale po želji, ali držite se malog broja objekata. Cilj je da po pikselu ray tracer barem radi **nekoliko** proračuna (primarni zrak, eventualno refleksija i senka), kako bi ispitivanje ubrzanja imalo smisla. Potpuno trivijalna scena (jedna sfera bez refleksije) možda bude toliko brza i na CPU da razlika nije dramatična; sa refleksijama i senkama, GPU bi trebalo da pokaže svoju snagu.

*Napomena:* Rezoluciju slike za testiranje podesite takođe razumno. Npr. 800x600 ili 1280x720 pixela je dobra osnova – to je ukupno \~0.5-1 milion piksela, što će CPU-u već predstavljati ozbiljniji posao, dok GPU može to relativno lako paralelizovati. Tako dobijate merljivo vreme na CPU (nekoliko sekundi možda), a GPU će to odraditi brže (možda ispod sekunde, zavisi od implementacije i GPU).

## 4. Build i pokretanje sistema, testiranje performansi CPU vs GPU

U finalu, imaćete dva načina rada aplikacije: CPU render i GPU render. Sada ćemo objasniti kako izgraditi ceo sistem, pokrenuti ga i uporediti brzine.

### 4.1. Izgradnja (kompajliranje) projekta

* **Java deo:** Kompajlirajte Java kod kao i obično (ako koristite IntelliJ, Build opcija; ako koristite Maven/Gradle, pokrenite odgovarajuću komandu). Uverite se da sve klase se uspešno kompajliraju, uključujući one koje ste dodali za GPU render.

* **CUDA/C++ deo:** Ako ste koristili JCuda, praktično nemate poseban build za native kod – dovoljno je da ste iskompajlirali CUDA kernel u PTX (ili ste se oslonili na runtime kompajliranje). Proverite da PTX fajl **RayTracerKernels.ptx** postoji na očekivanom mestu (npr. u radnom direktorijumu aplikacije ili u folderu sa resursima odakle ga učitavate).
  Ako ste koristili JNI sopstveni .dll, pobrinite se da ste **ispravno iskompajlirali .dll** (kao što je opisano ranije). Svaki put kad promenite .cu kod, morate ponovo pokrenuti `nvcc` kompilaciju. Automatski način: možete napraviti **tasks** u VS Code (npr. tasks.json) ili čak integrisati CMake za .dll. Za početak, ručno kompiliranje je dovoljno.

* **Lokacija biblioteka:** Postavite dobijeni `raytracer_gpu.dll` u neki od sledećih lokacija gde ga Java može naći:

  * U direktorijum sa .class/.jar fajlovima (ako pokrećete iz IntelliJ, to može biti `out/production/ProjName` ili sl).
  * Negde na PATH-u sistema (nije preporučljivo za trajno rešenje, ali za test možete dodati putanju do .dll u PATH pa će System.loadLibrary naći).
  * Koristite `System.load("C:\\full\\path\\to\\raytracer_gpu.dll")` sa apsolutnom putanjom umesto `loadLibrary`. Ovo je direktno, ali manje portabilno.
  * Kod JCuda, biblioteke su u jar-u i ekstraktuju se automatski, tako da tu nema ručnog posla osim da su jarovi na classpath-u.

* **Pokretanje JavaFX aplikacije:** Pokrenite klasu `gui.App` (ili kako se već zove glavni ulaz). Ako koristite IntelliJ, run konfiguracija će to odraditi. Ako koristite VS Code, možete napraviti launch konfiguraciju sa mainClass `xyz.marsavic.gfxlab.gui.App` i postaviti modulpath ako treba za JavaFX. Prilikom pokretanja, proverite da li se JavaFX prozor otvara i da li (u CPU režimu) dobijate sliku scene.

### 4.2. Testiranje GPU varijante

Sada aktivirajte GPU render. U zavisnosti kako ste to implemetirali:

* **JCuda varijanta:** Možda ste hardkodirali da uvek koristi GPU, ili imate parametar. Pokrenite aplikaciju tako da se izvrši JCuda sekvenca. Obratite pažnju na prve poruke – moguće je da JCuda ispiše određene logove. Ako se nešto kvari, uhvatićete izuzetke (npr. JCudaDriver cuInit grešku ili slično).
* **JNI varijanta:** Prilikom učitavanja biblioteke (`System.loadLibrary`), ako dođe do greške, proverite poruku – najčešći uzrok je da .dll nije pronađen ili da zavisna cudart64\_x.dll nije dostupna. Ako se to desi, rešenje: kopirajte odgovarajući cudart DLL (nalazi se u CUDA Toolkit bin folderu) pored vašeg `raytracer_gpu.dll` ili dodajte `CUDA_PATH/bin` u PATH.
* Kada GPU render krene, možda ćete želeti da prikažete neko obaveštenje ili log da znate da je GPU korišćen (npr. ispišite u konzolu "GPU rendering started...").

**Validacija rezultata:** Idealno, slika koju dobijete trebalo bi vizuelno da odgovara slici dobijenoj CPU renderom za istu scenu. Piksel do piksel bi moglo biti malo odstupanja zbog numeričkih razlika (float aritmetika na CPU vs GPU može dati vrlo blage razlike). Ali globalno, scena treba da izgleda isto (iste senke, boje, refleksije). Uporedite izlazne slike:

* Ako izgledaju drastično različito, verovatno postoji bug u jednom od rendrera (ili u prenosu podataka scene). Tada je korisno debugovati sa manjom rezolucijom i nekoliko objekata.
* Ako su slike u osnovi iste, čestitamo – implementirali ste ispravno i možemo preći na merenje performansi.

### 4.3. Merenje i poređenje performansi

Za kvantitativno poređenje CPU i GPU ray tracera, treba da izmerimo vreme renderovanja iste scene na istu rezoluciju na istom sistemu:

* **Merenje vremena:** U Java kodu, moguće je izmeriti proteklo vreme renderinga. Na primer:

  ```java
  long start = System.nanoTime();
  rendererCPU.render(scene, image);
  long cpuTime = System.nanoTime() - start;
  start = System.nanoTime();
  rendererGPU.render(scene, image);
  long gpuTime = System.nanoTime() - start;
  System.out.println("CPU render time: " + cpuTime/1e6 + " ms");
  System.out.println("GPU render time: " + gpuTime/1e6 + " ms");
  ```

  Obezbedite da obe metode renderuju *samo jedan frame* za fer poređenje (ako CPU renderer ima petlju za više framova ili animaciju, izolujte jedan okvir).
  **Napomena:** Za GPU merenje, uključite `cuCtxSynchronize()` ili `cudaDeviceSynchronize()` pre uzimanja završnog vremena, jer kernel lansiranje je asinhrono – `renderImage` JNI metoda u našem kodu je već čekala završetak pre kopiranja, tako da je dobro. Bitno je da vreme uključuje i kopiranje podataka sa GPU nazad, jer to je deo onoga što korisnik na kraju čeka.
* **"Warm-up" poziv:** Prvi poziv GPU koda uključuje određene fiksne troškove (inicijalizacija CUDA konteksta, alokacija memorije). Da bi poređenje bilo poštenije, možete ignorisati prvi okvir. Na primer, renderujte jednu sliku GPU-om, odbacite vreme, pa onda merite narednu. Za CPU to nije toliko bitno (osim JIT kompajliranja u Javi koje se desi prvih par poziva funkcija). Alternativno, merite prosečno vreme preko više iteracija.
* **Izvršite test na istim uslovima:** Pokrenite CPU render i zabeležite vreme. Potom pokrenite GPU render i zabeležite. Uradite to nekoliko puta i uzmite prosečne vrednosti. Uverite se da u međuvremenu sistem nema druge opterećenje (zatvorite nepotrebne aplikacije) da biste dobili konzistentne rezultate.

Na primer, očekivanja za scenu od \~3 reflektivne sfere na 1280x720:

* Ryzen 5 5600 (6 jezgara @ 3.5+ GHz) bi mogao CPU ray tracing (sa refleksijom i senkom) da renderuje za nekoliko sekundi po frame-u, zavisno od optimizacije.
* GTX 1060 (Pascal, \~1280 CUDA jezgara) bi trebalo to da ubrza značajno ako kernel koristi dovoljno paralelizma, možda iscrtavanje za ispod 1 sekunde.
* RTX 3080 (Ampere, 8704 CUDA jezgara) bi još brže trebalo da završi – potencijalno za par desetina milisekundi, pogotovo ako nije previše refleksija (Ray tracing alg. skala se sa brojem zraka, tako da ako je dubina refleksije mala, GPU će lako popuniti sve jezgre).
  Naravno, **stvarni brojevi** zavise od implementacije: memorijski pristup, divergentnost zraka itd. će uticati na GPU.

**Poređenje:** Nakon merenja, uporedite vremena i izračunajte akceleraciju. Npr:

* CPU: 5000 ms, GPU (1060): 1000 ms, GPU (3080): 200 ms. Tada GPU (1060) je \~5x brži od CPU za tu scenu, a RTX 3080 čak 25x brži od CPU. Takođe, RTX 3080 je \~5x brži od GTX 1060 u ovom zadatku.
* Ove rezultate možete prikazati u tabeli u radu, i analizirati. Zapazite kako se skalira sa rezolucijom: probajte i veću rezoluciju ili više objekata da vidite da li se faktori ubrzanja menjaju.

### 4.4. Dodatne optimizacije i RTX/DLSS razmatranja

U sklopu vašeg master rada, pomenuli ste mogućnost korišćenja RTX specifičnih jedinica i DLSS tehnologije. Ova integracija prevazilazi okvire klasične CUDA implementacije, ali daćemo kratak osvrt kako bi se to moglo uključiti:

* **RTX hardverska akceleracija (RT Cores):** RTX 3080 poseduje posebne RT jezgre namenjene za ubrzano prolaženje kroz strukture podataka za praćenje zraka (npr. bounding volume hijerarhije) i izračunavanje preseka zraka sa trouglovima vrlo efikasno. Da biste iskoristili RT jezgra, najdirektniji put je korišćenje NVIDIA **OptiX** ray tracing engine-a. OptiX obezbeđuje visokonivo API u kojem definišete scene (objekte, materijale) i tzv. *ray generation programs*, a sam OptiX iza kulisa koristi RT jezgra za ubrzanje. Integracija OptiX-a u vašu aplikaciju bi značila da napišete C++ kod koji koristi OptiX API umesto ručno pisanih intersect petlji. OptiX bi vam potencijalno dao ogromno ubrzanje za kompleksne scene, ali učenje i integracija nisu trivijalni. Ako imate vremena, možete eksperimentalno implementirati varijantu GPU rendera preko OptiX-a i uporediti. U kontekstu JNI integracije, morali biste praviti posebnu .dll koja poziva OptiX biblioteku. Dobra stvar je da OptiX podržava sfere, paralele i trouglove i sam gradi akceleracione strukture, tako da bi verno iscrtavanje scena sa mnogo objekata bilo mnogo brže nego čistim CUDA kernelom. U radu možete navesti da je OptiX put za korišćenje RTX jedinica.

* **DLSS (Deep Learning Super Sampling):** DLSS je NVIDIA tehnologija koja renderuje sliku na nižoj rezoluciji pa zatim koristi AI upscaling da dobije visoku rezoluciju sa kvalitetom bliskim nativnoj. U realnom vremenu ovo *dramatično povećava framerate*. U našem slučaju, teorijski, mogli biste renderovati sliku na, recimo, 640x360, pa je DLSS uveća na 1280x720 uz minimalan gubitak kvaliteta – što znači \~4x manje piksela za ray tracing. Međutim, korišćenje DLSS-a zahteva pristup NVIDIA NGX biblioteci i Tensor jezgrima. NVIDIA je izdala **Streamline SDK** koji olakšava integraciju DLSS-a u custom engine-e. U praksi, morali biste: (a) renderovati sliku i generisati i tzv. *motion vectors* i *depth buffer* scene (DLSS ih koristi za bolji upscaling), (b) pozvati DLSS API da obavi upscaling na GPU. Ovaj deo je vrlo kompleksan za implementaciju od nule i verovatno van domašaja jednostavne Java aplikacije. Ali možete konceptualno obraditi: npr. demonstrirati efekat tako što ćete ručno smanjiti rezoluciju rendera i skalirati up (koristeći najbliži sused ili neki filter) čisto radi merenja koliko bi to ubrzalo i kako utiče na kvalitet. Pravi DLSS bi dao mnogo bolji kvalitet od prostog skaliranja, ali zahteva integraciju zatvorenog koda (dostupnog kroz NVIDIA Developer program).

U zaključku testiranja, sumirajte rezultate:

* Koliko puta je GPU ubrzao renderovanje u odnosu na CPU za datu scenu.
* Kako se to razlikuje između GTX 1060 i RTX 3080 (pokazuje uticaj nove arhitekture i većeg broja jezgara; dodatno, i viši takt memorije na RTX).
* Razmislite i o upotrebljivosti: ako CPU-u treba 5 sekundi za sliku, a GPU to radi za 0.2 s, to može značiti mogućnost *interaktivnog* prikaza na GPU naspram skoro statičke slike na CPU.
* Osvrnite se i na *skalabilnost*: npr. ako dodate duplo više sfera ili povećate rezoluciju, da li se CPU usporava više nego GPU? (Tipično, GPU skalira bolje sa porastom opterećenja dok ima resursa.)
* Spomenite i troškove prelaska podataka CPU-GPU: za manje rezolucije overhead kopiranja može biti relativno značajan, dok za velike slike to postaje zanemarljivo u odnosu na račun.

Na kraju, uspeli ste da kroz konkretne korake ostvarite cilj: **Java ray tracing aplikacija proširena je GPU akceleracijom**. Dobili ste dragoceno iskustvo u heterogenom programiranju (Java+CUDA) i napravili osnovu za dalje eksperimente (dodavanje komplikovanijih scena, ispitivanje hardware RT jezgra, upscaling tehnika i sl.). Srećno sa daljim radom i dokumentovanjem rezultata!

**Reference:**

1. NVIDIA CUDA Toolkit Installation Guide for Windows – zahtevi i koraci instalacije.
2. Stack Overflow: Objašnjenje zavisnosti CUDA od MSVC kompajlera.
3. Reddit r/CUDA: Potreba za Visual Studio (MSVC) i korišćenje VS Code za CUDA razvoj.
4. JCuda dokumentacija – usklađenost verzije JCuda sa CUDA verzijom.
5. GfxLab README (marsavic/GfxLab-2022-2023) – podešavanje JavaFX okruženja (Liberica JDK).
6. NVIDIA Nsight VS Code ekstenzija – opis mogućnosti za CUDA razvoj.
7. NVIDIA OptiX Ray Tracing Engine – RTX akceleracija zraka preko RT jezgara.
8. NVIDIA Developer Blog – integracija DLSS u sopstveni engine preko Streamline SDK.
