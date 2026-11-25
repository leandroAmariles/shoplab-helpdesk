import os
import re
import unittest
import yaml

class InfraManifestsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.base_dir = os.path.abspath(
            os.path.join(os.path.dirname(__file__), "..", "shoplab-infra")
        )

    def test_applicationset_structure(self):
        appset_path = os.path.join(
            self.base_dir, "k8s", "bootstrap", "applicationset-services-dev.yaml"
        )
        self.assertTrue(os.path.exists(appset_path), f"Falta {appset_path}")

        with open(appset_path, "r", encoding="utf-8") as f:
            content = yaml.safe_load(f)

        self.assertEqual(content["kind"], "ApplicationSet")
        self.assertEqual(content["apiVersion"], "argoproj.io/v1alpha1")
        self.assertEqual(content["metadata"]["name"], "shoplab-services-dev")
        self.assertEqual(content["metadata"]["namespace"], "argocd")

        # Verificar generador de directorios Git
        generators = content["spec"]["generators"]
        self.assertTrue(len(generators) >= 1)
        git_gen = generators[0]["git"]
        self.assertEqual(git_gen["revision"], "main")
        dirs = [d["path"] for d in git_gen["directories"]]
        self.assertIn("apps/*/overlays/dev", dirs)

        # Verificar template
        template = content["spec"]["template"]
        self.assertEqual(template["spec"]["destination"]["namespace"], "shoplab-dev")
        self.assertEqual(template["spec"]["source"]["targetRevision"], "main")

        # Verificar políticas de sincronización
        sync_policy = template["spec"]["syncPolicy"]
        self.assertTrue(sync_policy["automated"]["prune"])
        self.assertTrue(sync_policy["automated"]["selfHeal"])

    def test_services_kustomize_structure(self):
        services = ["chat-service", "retrieval-service", "eval-service", "backend"]
        for svc in services:
            svc_dir = os.path.join(self.base_dir, "apps", svc)
            self.assertTrue(os.path.isdir(svc_dir), f"Falta directorio {svc_dir}")

            base_kust = os.path.join(svc_dir, "base", "kustomization.yaml")
            overlay_kust = os.path.join(svc_dir, "overlays", "dev", "kustomization.yaml")
            deployment = os.path.join(svc_dir, "base", "deployment.yaml")
            service = os.path.join(svc_dir, "base", "service.yaml")

            self.assertTrue(os.path.exists(base_kust), f"Falta {base_kust}")
            self.assertTrue(os.path.exists(overlay_kust), f"Falta {overlay_kust}")
            self.assertTrue(os.path.exists(deployment), f"Falta {deployment}")
            self.assertTrue(os.path.exists(service), f"Falta {service}")

            # Verificar overlay kustomization
            with open(overlay_kust, "r", encoding="utf-8") as f:
                overlay_data = yaml.safe_load(f)

            self.assertEqual(overlay_data["namespace"], "shoplab-dev")
            self.assertIn("../../base", overlay_data["resources"])
            self.assertTrue(len(overlay_data["images"]) >= 1)
            img = overlay_data["images"][0]
            self.assertFalse(img.get("newTag", "").endswith("latest"), f":latest prohibido en {svc}")
            self.assertTrue(re.match(r"^v\d+\.\d+\.\d+$", img.get("newTag", "")), f"Tag no semántico en {svc}")

    def test_external_secrets_isolation(self):
        db_services = ["chat-service", "retrieval-service", "eval-service"]
        for svc in db_services:
            secret_path = os.path.join(self.base_dir, "apps", svc, "base", "externalsecret.yaml")
            self.assertTrue(os.path.exists(secret_path), f"Falta ExternalSecret en {svc}")

            with open(secret_path, "r", encoding="utf-8") as f:
                data = yaml.safe_load(f)

            self.assertEqual(data["kind"], "ExternalSecret")
            self.assertEqual(data["metadata"]["name"], f"{svc}-db-credentials")
            self.assertEqual(data["spec"]["target"]["name"], f"{svc}-db-secret")

            # Validar que apunta exclusivamente a su ruta de secretos
            for item in data["spec"]["data"]:
                expected_prefix = f"shoplab/dev/{svc}/database"
                self.assertEqual(item["remoteRef"]["key"], expected_prefix, f"Secret cruzado detectado en {svc}")

    def test_deployment_health_probes(self):
        services = ["chat-service", "retrieval-service", "eval-service", "backend"]
        for svc in services:
            dep_path = os.path.join(self.base_dir, "apps", svc, "base", "deployment.yaml")
            with open(dep_path, "r", encoding="utf-8") as f:
                dep_data = yaml.safe_load(f)

            container = dep_data["spec"]["template"]["spec"]["containers"][0]
            self.assertIn("livenessProbe", container, f"Falta livenessProbe en {svc}")
            self.assertIn("readinessProbe", container, f"Falta readinessProbe en {svc}")
            self.assertEqual(container["livenessProbe"]["httpGet"]["path"], "/actuator/health/liveness")
            self.assertEqual(container["readinessProbe"]["httpGet"]["path"], "/actuator/health/readiness")

    def test_deploy_script_rebase_retry_logic(self):
        script_path = os.path.join(self.base_dir, "scripts", "deploy-service.sh")
        self.assertTrue(os.path.exists(script_path))

        with open(script_path, "r", encoding="utf-8") as f:
            script_text = f.read()

        # Verificar elementos clave del contrato de rebase
        self.assertIn("git pull --rebase origin main", script_text)
        self.assertIn("MAX_RETRIES=3", script_text)
        self.assertIn("kustomize edit set image", script_text)
        self.assertIn("policy-check.sh", script_text)

    def test_policy_check_script(self):
        policy_path = os.path.join(self.base_dir, "scripts", "policy-check.sh")
        self.assertTrue(os.path.exists(policy_path))

        with open(policy_path, "r", encoding="utf-8") as f:
            policy_text = f.read()

        self.assertIn("image:[ \\t]*[^:]+:latest", policy_text)
        self.assertIn("SERVICE_NAME", policy_text)

if __name__ == "__main__":
    unittest.main()
