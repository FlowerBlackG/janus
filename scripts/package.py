import sys
import os
import requests
import argparse
import dataclasses
import zipfile
import tarfile
from enum import Enum
from pathlib import Path
import subprocess
import shutil
from typing import Callable, Any
import stat


class ArchiveType(Enum):
    TGZ = 1
    ZIP = 2

class ShellType(Enum):
    BASH = 1
    BAT = 2

@dataclasses.dataclass
class JDK:
    id: str
    download_url: str
    archive_type: ArchiveType

@dataclasses.dataclass
class Platform:
    key: str
    jdk: JDK
    jar_platform_classifier: str
    shell_type: ShellType
    compress_to: ArchiveType


PROJECT_ROOT = Path(__file__).parent.parent
LIBS_DIR = PROJECT_ROOT / "build" / "libs"
APP_NAME = "janus"
WORK_DIR = PROJECT_ROOT / "target" / "packaging"
TMP_DIR = WORK_DIR / "tmp"
OUTPUT_DIR = WORK_DIR / "output"
JDK_DIR = WORK_DIR / "jdks"


platforms = {
    "windows-x86_64": Platform(
        key="windows-x86_64",
        jdk=JDK(
            id="kona-25.0.1-windows-x86_64",
            download_url="https://github.com/Tencent/TencentKona-25/releases/download/TencentKona-25.0.1/TencentKona-25.0.1.b1_jdk_windows-x86_64_signed.zip",
            archive_type=ArchiveType.ZIP,
        ),
        jar_platform_classifier="windows-x86_64",
        shell_type=ShellType.BAT,
        compress_to=ArchiveType.ZIP,
    ),

    "linux-x86_64": Platform(
        key="linux-x86_64",
        jdk=JDK(
            id="kona-25.0.1-linux-x86_64",
            download_url="https://github.com/Tencent/TencentKona-25/releases/download/TencentKona-25.0.1/TencentKona-25.0.1.b1-jdk_linux-x86_64.tar.gz",
            archive_type=ArchiveType.TGZ,
        ),
        jar_platform_classifier="linux-x86_64",
        shell_type=ShellType.BASH,
        compress_to=ArchiveType.TGZ,
    ),
}


def generate_bash_script(java_exec_path: str, jar_path: str) -> str:
    result = "#!/bin/bash\n" 
    result += f"{java_exec_path} --enable-native-access=ALL-UNNAMED -jar {jar_path} \"$@\"\n"
    return result


def generate_bat_script(java_exec_path: str, jar_path: str) -> str:
    result = f"@echo off\r\n"
    result += f"{java_exec_path} --enable-native-access=ALL-UNNAMED -jar {jar_path} %*\r\n"
    return result


def gradle_package_all() -> int:
    gradle_exe = "gradlew" if os.name != "nt" else "gradlew.bat"
    gradle_exe_path = PROJECT_ROOT / gradle_exe

    process = subprocess.run(
        [gradle_exe_path.absolute(), "packageAll"],
        cwd=PROJECT_ROOT,
    )

    return process.returncode


def remove_readonly(func: Callable[..., Any], path: str, excinfo: Any) -> None:
    """Clear the readonly bit and re-attempt the file removal."""
    os.chmod(path, stat.S_IWRITE)
    func(path)


def package_platform(platform: Platform, version_tag: str) -> int:
    # 1. Ensure directories exist
    shutil.rmtree(TMP_DIR, onexc=remove_readonly)
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    JDK_DIR.mkdir(parents=True, exist_ok=True)
    TMP_DIR.mkdir(parents=True, exist_ok=True)

    # platform_tmp_folder: The working directory for this specific platform
    platform_tmp_folder = TMP_DIR / platform.key
    if platform_tmp_folder.exists():
        shutil.rmtree(platform_tmp_folder, onexc=remove_readonly)
    platform_tmp_folder.mkdir(parents=True)

    # 2. Download JDK
    print(f"[{platform.key}] Downloading JDK...")
    extension = 'zip' if platform.jdk.archive_type == ArchiveType.ZIP else 'tar.gz'
    jdk_archive_path = JDK_DIR / f"{platform.jdk.id}.{extension}"
    jdk_archive_path_tmp = JDK_DIR / f"jdk.part"

    if jdk_archive_path_tmp.exists():
        jdk_archive_path_tmp.unlink()
    

    if jdk_archive_path.exists():
        print(f"[{platform.key}] JDK found in cache, skipping download.")
    else:
        with requests.get(platform.jdk.download_url, stream=True) as r:
            r.raise_for_status()
            total_size = int(r.headers.get('content-length', 0))
            block_size = 1024 * 1024  # 1MB
            downloaded = 0
            
            with open(jdk_archive_path_tmp, 'wb') as f:
                for data in r.iter_content(block_size):
                    f.write(data)
                    downloaded += len(data)
                    if total_size > 0:
                        done = int(50 * downloaded / total_size)
                        # Use \r to overwrite the same line in the console
                        sys.stdout.write(f"\r[{'=' * done}{' ' * (50-done)}] {downloaded / (1024*1024):.2f}/{total_size / (1024*1024):.2f} MB")
                        sys.stdout.flush()
            sys.stdout.write("\n") # New line after download completion

        jdk_archive_path_tmp.rename(jdk_archive_path)

    # 3. Unzip/Extract JDK into platform_tmp_folder
    print(f"[{platform.key}] Extracting JDK...")
    if platform.jdk.archive_type == ArchiveType.ZIP:
        with zipfile.ZipFile(jdk_archive_path, 'r') as zip_ref:
            zip_ref.extractall(platform_tmp_folder)
    else:
        with tarfile.open(jdk_archive_path, 'r:gz') as tar_ref:
            # Using filter='data' to resolve Python 3.14 DeprecationWarning
            # This is available in Python 3.12+ and 3.11.4+
            try:
                tar_ref.extractall(platform_tmp_folder, filter='data')
            except TypeError:
                # Fallback for older Python versions that don't support the filter argument
                tar_ref.extractall(platform_tmp_folder)

    # 4. Copy JAR from LIBS_DIR to platform_tmp_folder
    jar_name = f"janus-{version_tag}-{platform.jar_platform_classifier}.jar"
    source_jar = LIBS_DIR / jar_name
    dest_jar = platform_tmp_folder / jar_name

    if not source_jar.exists():
        print(f"Error: JAR not found at {source_jar}")
        return 1
    
    shutil.copy2(source_jar, dest_jar)

    # 5. Find bin/java executable relative to platform_tmp_folder
    java_bin_path = None
    for p in platform_tmp_folder.rglob("bin/java*"):
        if platform.shell_type == ShellType.BAT and p.name == "java.exe":
            java_bin_path = p.relative_to(platform_tmp_folder)
            break
        elif platform.shell_type == ShellType.BASH and p.name == "java":
            java_bin_path = p.relative_to(platform_tmp_folder)
            break
    
    if not java_bin_path:
        print(f"Error: Could not find java executable in {platform_tmp_folder}")
        return 1

    # 6. Create run script
    script_base_name = f"run-janus"
    if platform.shell_type == ShellType.BAT:
        script_name = f"{script_base_name}.bat"
        content = generate_bat_script(str(java_bin_path), jar_name)
    else:
        script_name = f"{script_base_name}.sh"
        # Prefix with ./ for the java path in the bash script
        content = generate_bash_script(f"./{java_bin_path}", jar_name)

    script_path = platform_tmp_folder / script_name
    script_path.write_text(content)
    
    if platform.shell_type == ShellType.BASH:
        script_path.chmod(0o755)

    # 7. Move platform_tmp_folder to OUTPUT_DIR and rename
    final_dir_name = f"janus-{version_tag}-{platform.key}"
    final_path = OUTPUT_DIR / final_dir_name
    
    if final_path.exists():
        shutil.rmtree(final_path)
    
    shutil.move(str(platform_tmp_folder), str(final_path))
    print(f"[{platform.key}] Package created at: {final_path}")

    # 8. Compress the final_path into archive
    archive_name = f"{final_dir_name}.{'zip' if platform.compress_to == ArchiveType.ZIP else 'tar.gz'}"
    archive_path = OUTPUT_DIR / archive_name
    print(f"[{platform.key}] Compressing package to {archive_path}...")
    if platform.compress_to == ArchiveType.ZIP:
        with zipfile.ZipFile(archive_path, 'w', zipfile.ZIP_DEFLATED) as zipf:
            for root, _, files in os.walk(final_path):
                for file in files:
                    file_path = Path(root) / file
                    zipf.write(file_path, file_path.relative_to(final_path))
    else:
        with tarfile.open(archive_path, "w:gz") as tar:
            tar.add(final_path, arcname=final_dir_name)

    return 0


def main() -> int:
    argparser = argparse.ArgumentParser(description="Package Janus for deployment.")
    argparser.add_argument("--version", type=str, required=True, help="Version tag for the package. Like: 0.0.1")
    args = argparser.parse_args()

    res = gradle_package_all()
    if res != 0:
        return res
    
    for platform_key, platform_data in platforms.items():
        print(f"\n--- Packaging for {platform_key} ---")
        res = package_platform(platform_data, args.version)
        if res != 0:
            print(f"Failed to package {platform_key}")
            return res

    print("\nAll platforms packaged successfully.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
