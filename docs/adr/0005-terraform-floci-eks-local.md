# ADR-0005: Terraform sobre Floci para practicar un EKS local

- Estado: aceptado
- Fecha: 2026-10-24

## Contexto

LAB-98 pide crear la infraestructura con Terraform contra un AWS emulado en local, para practicar el flujo de un EKS real sin pagar nube y reconstruir el entorno con un solo comando. El entorno debe tener estado remoto con bloqueo, un clúster Kubernetes, secretos en Secrets Manager y SSM, y una plataforma base (ingress, External Secrets, Argo CD y monitorización). Todo corre en Docker, sin cuenta de AWS. El código vive en el repositorio `shoplab-infra`.

Alternativas evaluadas:

- **kind o k3d solos**: levantan un clúster Kubernetes, pero no emulan AWS. No se practica el módulo `eks`, el estado en S3 ni Secrets Manager/SSM, así que se pierde la parte de "mismo flujo que en un EKS real".
- **LocalStack**: emula AWS ampliamente, pero su emulación de EKS y parte de sus funciones dependen del plan comercial y de una cuenta; no se verificó en este laboratorio. Se descartó para no depender de cuentas ni de cambios de licencia.
- **Floci** (licencia MIT, sin cuenta ni límites de funciones): emula S3, Secrets Manager, SSM, IAM, EC2 y EKS, y crea el EKS sobre k3s real.

## Decisión

Usar **Terraform + Floci** con la imagen fijada a una versión estable (`floci/floci:2.3.0`, nunca `latest` ni los tags `nightly-*`) y la imagen de k3s fijada también (`rancher/k3s:v1.35.9-k3s1`). El estado remoto vive en un bucket S3 emulado con bloqueo nativo (`use_lockfile`). El código se divide en los módulos `state`, `eks`, `secrets` y `platform`, con un entorno raíz por ambiente y una raíz de bootstrap para crear el bucket de estado.

El módulo `eks` admite un **modo de respaldo con kind** (`cluster_mode = "kind"`) con el mismo contrato de salida, para no quedar bloqueado si el EKS de Floci falla.

Resultados del spike (2026-10-24, Floci 2.3.0, Terraform 1.15.8, provider `aws` 6.70.0):

- El bloqueo `use_lockfile` funciona: un segundo `apply` simultáneo falla con `412 PreconditionFailed` sin corromper el estado.
- `aws_eks_cluster` crea el clúster en unos 17 s y devuelve endpoint y CA. Requiere VPC, subred y rol IAM.
- El token de `aws_eks_cluster_auth` se genera con `X-Amz-Expires=0` y la API de k3s lo rechaza (`401`). El kubeconfig se construye con el certificado de cliente que k3s deja en el contenedor `floci-aws-eks-<cluster>`.

## Consecuencias

- Se practica el flujo completo de Terraform (estado remoto, módulos, providers `helm` y `kubernetes`) sin coste ni cuenta de AWS.
- Dependemos de un emulador joven: hay que fijar versiones y revisar los cambios de Floci antes de subirlas. Si el EKS emulado falla, el modo kind mantiene el resto del flujo.
- El kubeconfig depende de `docker exec` sobre el contenedor de k3s, de modo que `eks` requiere la CLI de Docker y no sirve contra un Floci remoto.
- Floci crea contenedores y redes fuera de compose (`floci-aws-*`); `scripts/down.sh` los limpia.
- El estado remoto contiene los valores de los secretos en claro; vive solo en el emulador local y nunca en git.
- `ingress-nginx` está en retiro por parte de su proyecto (la última versión del chart es la 4.15.1); es aceptable para un laboratorio local, pero habría que sustituirlo (por ejemplo por Gateway API) en un entorno real.
- Ejecutar Terraform en CI queda fuera de alcance de esta decisión.
