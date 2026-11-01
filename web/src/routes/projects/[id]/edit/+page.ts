export const prerender = false;

export function load({ params }: { params: { id: string } }) {
  const projectId = Number(params.id);
  return {
    projectId: Number.isSafeInteger(projectId) && projectId > 0 ? projectId : 0,
  };
}
